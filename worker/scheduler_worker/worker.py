"""The worker loop: claim a job, train it, report the result, repeat."""

import logging
import os
import time
from collections.abc import Callable
from typing import Any

from . import task
from .backoff import Backoff
from .client import ApiClient, ApiError, ApiUnavailable, AttemptRejected
from .config import Assignment, InvalidAssignment, MlpConfig, WorkerSettings
from .heartbeat import Heartbeat

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
        log.info("worker %s polling %s", self._settings.worker_id, self._settings.api_url)
        idle = Backoff(self._settings.poll_initial_seconds, self._settings.poll_max_seconds)
        while not self.stop_requested:
            try:
                raw = self._client.claim(self._settings.worker_id)
            except (ApiUnavailable, ApiError) as exc:
                log.warning("claim failed: %s", exc)
                self._pause(idle.next())
                continue
            if raw is None:
                self._pause(idle.next())
                continue
            idle.reset()
            self._execute(raw)
        log.info("worker %s stopped", self._settings.worker_id)

    def _execute(self, raw: dict[str, Any]) -> None:
        try:
            assignment = Assignment.from_json(raw)
        except InvalidAssignment as exc:
            # Without a heartbeat the lease runs out, so the job is retried up to its attempt budget
            # and then FAILED. Reporting the failure directly arrives in increment 2.2.
            log.error("job=%s cannot run: %s; not heartbeating, so its lease will expire", raw.get("jobId"), exc)
            return
        tag = f"job={assignment.job_id} attempt={assignment.attempt_number}"
        log.info("%s experiment=%d seed=%d training %s", tag, assignment.experiment_id, assignment.seed,
                 _describe_config(assignment.config))
        heartbeat = Heartbeat(self._client, assignment.job_id, assignment.attempt_id,
                              interval=assignment.heartbeat_interval_seconds, lease_seconds=assignment.lease_seconds)
        # The lease must stay alive until the result is acknowledged, so heartbeats cover reporting too.
        with heartbeat:
            try:
                metrics = self._train(assignment.config, assignment.seed,
                                      should_stop=lambda: self.abort_requested or heartbeat.lost.is_set())
            except task.TrainingStopped:
                if heartbeat.lost.is_set():
                    log.warning("%s lease lost: %s; training stopped, nothing reported", tag, heartbeat.reason)
                else:
                    log.warning("%s aborted before finishing; not reported (its lease will expire)", tag)
                return
            except task.TrainingDiverged as exc:
                log.error("%s diverged (%s); not reported (failure reports arrive in increment 2.2)", tag, exc)
                return
            if heartbeat.lost.is_set():
                log.warning("%s lease lost as training finished: %s; result discarded", tag, heartbeat.reason)
                return
            try:
                self._client.complete(assignment.job_id, assignment.attempt_id, metrics.to_json())
            except AttemptRejected as exc:
                log.warning("%s result rejected (%s; job is %s); discarded", tag, exc.code, exc.job_state)
                return
            except (ApiUnavailable, ApiError) as exc:
                log.error("%s result could not be reported: %s; discarded", tag, exc)
                return
        log.info("%s done: valAccuracy=%.4f valLoss=%.4f trainingSeconds=%.2f", tag, metrics.val_accuracy,
                 metrics.val_loss, metrics.training_seconds)

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
