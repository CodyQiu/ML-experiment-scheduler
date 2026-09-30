"""The worker loop: claim a job, train it, report the result, repeat."""

import logging
import os
import time
from collections.abc import Callable
from typing import Any
from uuid import UUID

from . import task
from .backoff import Backoff
from .client import ApiClient, ApiError, ApiUnavailable, AttemptRejected
from .config import Assignment, InvalidAssignment, MlpConfig, WorkerSettings, identify
from .heartbeat import Heartbeat
from .logs import AttemptLog

log = logging.getLogger(__name__)

TrainFn = Callable[..., task.Metrics]


class Worker:
    """Runs one job at a time until asked to stop.

    Shutdown happens in two stages:
    - The first SIGTERM/SIGINT stops claiming. An idle worker exits within 0.2 s; a busy one first
      finishes and reports its current job.
    - A second signal also aborts the current training run at the next minibatch. Nothing is
      reported; the job's lease runs out, and recovery retries it (or fails it on its last attempt).
    """

    def __init__(self, settings: WorkerSettings, client: ApiClient, *, train: TrainFn = task.train,
                 sleep: Callable[[float], None] = time.sleep):
        self._settings = settings
        self._client = client
        self._train = train
        self._sleep = sleep
        self.stop_requested = False
        self.abort_requested = False

    def handle_signal(self, signum: int = 0, frame: object = None) -> None:
        """Signal handler. It only assigns flags and writes to fd 2. Taking a lock here (logging and
        threading.Event both do) could deadlock with the code the signal interrupted."""
        if self.stop_requested:
            self.abort_requested = True
            os.write(2, b"second stop signal: aborting the current job\n")
        else:
            self.stop_requested = True
            os.write(2, b"stop signal: finishing the current job, then exiting (send again to abort)\n")

    def run(self) -> None:
        log.info("worker %s polling %s", self._settings.worker_id, self._settings.api_url,
                 extra={"event.action": "worker.started", "apiUrl": self._settings.api_url})
        idle = Backoff(self._settings.poll_initial_seconds, self._settings.poll_max_seconds)
        while not self.stop_requested:
            try:
                raw = self._client.claim(self._settings.worker_id)
            except (ApiUnavailable, ApiError) as exc:
                log.warning("claim failed: %s", exc, extra={"event.action": "claim.failed"})
                self._pause(idle.next())
                continue
            if raw is None:
                self._pause(idle.next())
                continue
            idle.reset()
            self._execute(raw)
        log.info("worker %s stopped", self._settings.worker_id, extra={"event.action": "worker.stopped"})

    def _execute(self, raw: dict[str, Any]) -> None:
        """Runs one claimed attempt to one outcome: a reported success, a reported failure, or
        nothing at all when the lease was lost (the attempt no longer has anything to report)."""
        try:
            assignment = Assignment.from_json(raw)
        except InvalidAssignment as exc:
            self._report_unrunnable(raw, exc)
            return
        alog = AttemptLog(log, f"job={assignment.job_id} attempt={assignment.attempt_number}", {
            "experimentId": assignment.experiment_id, "jobId": assignment.job_id,
            "attemptNumber": assignment.attempt_number, "attemptId": str(assignment.attempt_id)})
        alog.info("experiment=%d seed=%d training %s", assignment.experiment_id, assignment.seed,
                  _describe_config(assignment.config),
                  extra={"event.action": "training.started", "seed": assignment.seed})
        heartbeat = Heartbeat(self._client, assignment.job_id, assignment.attempt_id,
                              interval=assignment.heartbeat_interval_seconds, lease_seconds=assignment.lease_seconds)
        # The lease must stay alive until the outcome is acknowledged, so heartbeats cover reporting too.
        with heartbeat:
            try:
                metrics = self._train(assignment.config, assignment.seed,
                                      should_stop=lambda: self.abort_requested or heartbeat.lost.is_set())
            except task.TrainingStopped:
                if heartbeat.lost.is_set():
                    alog.warning("lease lost: %s; training stopped, nothing reported", heartbeat.reason,
                                 extra={"event.action": "lease.lost", "reason": heartbeat.reason})
                else:
                    # Not the job's fault, so worth another attempt, and reporting it hands the job
                    # over now instead of after the lease runs out.
                    self._report_failure(assignment, alog, retryable=True, error_type="WORKER_SHUTDOWN",
                                         message="training aborted by a second stop signal")
                return
            except task.TrainingDiverged as exc:
                # Deterministic for this config and seed, so another attempt would diverge the same way.
                self._report_failure(assignment, alog, retryable=False, error_type="TRAINING_DIVERGED",
                                     message=str(exc))
                return
            except Exception as exc:  # any other error ends this job, not the worker
                alog.exception("training raised an unexpected error", extra={"event.action": "training.error"})
                self._report_failure(assignment, alog, retryable=True, error_type="WORKER_ERROR",
                                     message=f"{type(exc).__name__}: {exc}")
                return
            if heartbeat.lost.is_set():
                alog.warning("lease lost as training finished: %s; result discarded", heartbeat.reason,
                             extra={"event.action": "lease.lost", "reason": heartbeat.reason})
                return
            self._report_success(assignment, alog, metrics)

    def _report_success(self, assignment: Assignment, alog: AttemptLog, metrics: task.Metrics) -> None:
        try:
            replayed = self._client.complete(assignment.job_id, assignment.attempt_id, metrics.to_json())
        except AttemptRejected as exc:
            alog.warning("result rejected (%s; job is %s); discarded", exc.code, exc.job_state,
                         extra={"event.action": "result.rejected", "code": exc.code, "jobState": exc.job_state})
            return
        except (ApiUnavailable, ApiError) as exc:
            alog.error("result could not be reported: %s; discarded (its lease will expire)", exc,
                       extra={"event.action": "result.undeliverable"})
            return
        alog.info("done%s: valAccuracy=%.4f valLoss=%.4f trainingSeconds=%.2f",
                  " (acknowledged on retry)" if replayed else "", metrics.val_accuracy, metrics.val_loss,
                  metrics.training_seconds,
                  extra={"event.action": "result.accepted", "replayed": replayed, "valAccuracy": metrics.val_accuracy,
                         "valLoss": metrics.val_loss, "trainingSeconds": metrics.training_seconds})

    def _report_failure(self, assignment: Assignment | tuple[int, UUID], alog: AttemptLog, *, retryable: bool,
                        error_type: str, message: str) -> None:
        job_id, attempt_id = ((assignment.job_id, assignment.attempt_id) if isinstance(assignment, Assignment)
                              else assignment)
        try:
            state = self._client.fail(job_id, attempt_id, retryable=retryable, error_type=error_type, message=message)
        except AttemptRejected as exc:
            alog.warning("failure report rejected (%s; job is %s)", exc.code, exc.job_state,
                         extra={"event.action": "failure.rejected", "code": exc.code, "jobState": exc.job_state})
            return
        except (ApiUnavailable, ApiError) as exc:
            alog.error("failure report could not be delivered: %s; its lease will expire", exc,
                       extra={"event.action": "failure.undeliverable", "errorType": error_type})
            return
        alog.warning("reported %s (retryable=%s): %s; job is now %s", error_type, retryable, message, state,
                     extra={"event.action": "failure.recorded", "errorType": error_type, "retryable": retryable,
                            "jobState": state})

    def _report_unrunnable(self, raw: dict[str, Any], exc: InvalidAssignment) -> None:
        identity = identify(raw)
        if identity is None:
            # Without a usable job and attempt id there is nothing to report on.
            log.error("unusable assignment (%s); cannot report it, so its lease will expire", exc,
                      extra={"event.action": "assignment.unusable"})
            return
        # Another worker of this version would reject it the same way, so a retry would not help.
        job_id, attempt_id = identity
        alog = AttemptLog(log, f"job={job_id}", {"jobId": job_id, "attemptId": str(attempt_id)})
        self._report_failure(identity, alog, retryable=False, error_type="INVALID_ASSIGNMENT", message=str(exc))

    def _pause(self, seconds: float) -> None:
        """Sleeps in short slices, so a stop request is noticed within 0.2 s."""
        remaining = seconds
        while remaining > 0 and not self.stop_requested:
            step = min(0.2, remaining)
            self._sleep(step)
            remaining -= step


def _describe_config(config: MlpConfig) -> str:
    return (f"{config.optimizer} lr={config.learning_rate:g} {config.hidden_units}x{config.hidden_layers} "
            f"batch={config.batch_size} epochs={config.epochs} wd={config.weight_decay:g}")
