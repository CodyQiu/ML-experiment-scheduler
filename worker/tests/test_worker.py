"""The worker loop against a fake API and a fake training function."""

import copy
import time
from uuid import uuid4

from scheduler_worker import task
from scheduler_worker.client import ApiUnavailable, AttemptRejected
from scheduler_worker.config import WorkerSettings
from scheduler_worker.worker import Worker

SETTINGS = WorkerSettings(api_url="http://api", worker_id="w-1", poll_initial_seconds=0.5, poll_max_seconds=4.0,
                          connect_timeout_seconds=1.0, read_timeout_seconds=1.0, report_attempts=3, torch_threads=1)
METRICS = task.Metrics(val_accuracy=0.9, val_loss=0.25, train_loss=0.2, training_seconds=1.5)


def assignment(job_id, task_id="synthetic-mlp-v1", heartbeat_interval=60.0, lease=180.0):
    return {"jobId": job_id, "attemptId": str(uuid4()), "attemptNumber": 1, "experimentId": 1, "task": task_id,
            "seed": 0, "leaseSeconds": lease, "heartbeatIntervalSeconds": heartbeat_interval, "config": {"learningRate": 0.01, "hiddenUnits": 16, "hiddenLayers": 1, "batchSize": 64,
                                  "epochs": 1, "optimizer": "adam", "weightDecay": 0.0}}


class FakeApi:
    """Replays scripted claim results (an assignment, None, or an exception to raise). Once the
    script runs out, it stops the worker, which ends the test run."""

    def __init__(self, *claims, complete_error=None, heartbeat_error=None):
        self.claims = list(claims)
        self.claim_calls = 0
        self.completed = []
        self.complete_error = complete_error
        self.heartbeats = 0
        self.heartbeat_error = heartbeat_error
        self.failures = []
        self.worker = None

    def claim(self, worker_id):
        self.claim_calls += 1
        if not self.claims:
            self.worker.stop_requested = True
            return None
        item = self.claims.pop(0)
        if isinstance(item, Exception):
            raise item
        return copy.deepcopy(item)

    def complete(self, job_id, attempt_id, metrics):
        self.completed.append((job_id, str(attempt_id), metrics))
        if self.complete_error:
            raise self.complete_error

    def heartbeat(self, job_id, attempt_id):
        self.heartbeats += 1
        if self.heartbeat_error:
            raise self.heartbeat_error

    def fail(self, job_id, attempt_id, *, retryable, error_type, message):
        self.failures.append((job_id, error_type, retryable))
        return "QUEUED" if retryable else "FAILED"


def make_worker(api, train=None):
    """A worker that records how long it asks to pause instead of sleeping."""
    worker = Worker(SETTINGS, api, train=train or (lambda config, seed, should_stop: METRICS))
    pauses = []
    worker._pause = pauses.append
    api.worker = worker
    return worker, pauses


def completed_jobs(api):
    return [job_id for job_id, _, _ in api.completed]


def test_runs_each_claimed_job_and_reports_its_metrics():
    first, second = assignment(1), assignment(2)
    api = FakeApi(first, second)
    worker, _ = make_worker(api)

    worker.run()

    assert api.completed == [(1, first["attemptId"], METRICS.to_json()), (2, second["attemptId"], METRICS.to_json())]


def test_idle_polling_backs_off_exponentially_and_resets_after_work():
    api = FakeApi(None, None, None, assignment(1), None)
    worker, pauses = make_worker(api)

    worker.run()

    assert completed_jobs(api) == [1]
    # Waits are jittered within [ceiling/2, ceiling]: ceilings 0.5, 1, 2, then back to 0.5 after the job.
    assert [0.25 <= pauses[0] <= 0.5, 0.5 <= pauses[1] <= 1.0, 1.0 <= pauses[2] <= 2.0,
            0.25 <= pauses[3] <= 0.5] == [True] * 4


def test_idle_backoff_is_capped():
    api = FakeApi(*[None] * 12)
    worker, pauses = make_worker(api)

    worker.run()

    assert max(pauses) <= SETTINGS.poll_max_seconds
    assert pauses[-1] >= SETTINGS.poll_max_seconds / 2


def test_keeps_polling_while_the_api_is_unavailable():
    api = FakeApi(ApiUnavailable("down"), ApiUnavailable("down"), assignment(1))
    worker, pauses = make_worker(api)

    worker.run()

    assert completed_jobs(api) == [1]
    assert len(pauses) >= 2


def test_a_rejected_result_is_discarded_and_the_loop_continues():
    api = FakeApi(assignment(1), assignment(2), complete_error=AttemptRejected(1, "ATTEMPT_NOT_CURRENT", "SUCCEEDED", "not running"))
    worker, _ = make_worker(api)

    worker.run()

    assert completed_jobs(api) == [1, 2]


def test_an_assignment_it_cannot_run_is_reported_as_a_non_retryable_failure_without_training():
    trained = []
    api = FakeApi(assignment(1, task_id="mnist"), assignment(2))
    worker, _ = make_worker(api, train=lambda config, seed, should_stop: trained.append(seed) or METRICS)

    worker.run()

    assert api.failures == [(1, "INVALID_ASSIGNMENT", False)]
    assert len(trained) == 1
    assert completed_jobs(api) == [2]


def test_an_assignment_without_a_usable_attempt_id_cannot_even_be_reported():
    api = FakeApi({**assignment(1), "attemptId": "not-a-uuid"})
    worker, _ = make_worker(api)

    worker.run()

    assert api.failures == [] and api.completed == []


def test_a_diverged_run_is_reported_as_a_non_retryable_failure():
    def train(config, seed, should_stop):
        raise task.TrainingDiverged("validation loss is nan")

    api = FakeApi(assignment(1))
    worker, _ = make_worker(api, train=train)
    worker.run()

    assert api.failures == [(1, "TRAINING_DIVERGED", False)]
    assert api.completed == []


def test_an_unexpected_error_is_reported_as_retryable_and_the_worker_keeps_going():
    calls = []

    def train(config, seed, should_stop):
        calls.append(seed)
        if len(calls) == 1:
            raise RuntimeError("out of scratch space")
        return METRICS

    api = FakeApi(assignment(1), assignment(2))
    worker, _ = make_worker(api, train=train)
    worker.run()

    assert api.failures == [(1, "WORKER_ERROR", True)]
    assert completed_jobs(api) == [2]


def test_first_stop_signal_finishes_and_reports_the_current_job_then_exits():
    api = FakeApi(assignment(1), assignment(2))

    def train(config, seed, should_stop):
        worker.handle_signal()  # SIGTERM arrives mid-training
        assert not should_stop()
        return METRICS

    worker, _ = make_worker(api, train=train)
    worker.run()

    assert completed_jobs(api) == [1]
    assert api.claim_calls == 1


def test_second_stop_signal_aborts_training_and_hands_the_job_back():
    api = FakeApi(assignment(1), assignment(2))

    def train(config, seed, should_stop):
        worker.handle_signal()
        worker.handle_signal()
        if should_stop():
            raise task.TrainingStopped("aborted")
        return METRICS

    worker, _ = make_worker(api, train=train)
    worker.run()

    assert api.completed == []
    # Reported as retryable, so the job is re-queued at once instead of after its lease runs out.
    assert api.failures == [(1, "WORKER_SHUTDOWN", True)]
    assert api.claim_calls == 1


def test_a_pause_ends_within_one_slice_of_a_stop_request():
    slices = []

    def sleep(seconds):
        slices.append(seconds)
        if len(slices) == 2:
            worker.handle_signal()

    worker = Worker(SETTINGS, FakeApi(), sleep=sleep)
    worker._pause(5.0)

    assert slices == [0.2, 0.2]


def train_until_stopped(config, seed, should_stop):
    """A fake training run that keeps going (for up to 5 s) until it is told to stop."""
    for _ in range(1000):
        if should_stop():
            raise task.TrainingStopped("stopped")
        time.sleep(0.005)
    return METRICS


def test_heartbeats_keep_the_lease_alive_while_training():
    api = FakeApi(assignment(1, heartbeat_interval=0.01, lease=1.0))

    def train(config, seed, should_stop):
        time.sleep(0.1)
        assert not should_stop()
        return METRICS

    worker, _ = make_worker(api, train=train)
    worker.run()

    assert api.heartbeats >= 3
    assert completed_jobs(api) == [1]


def test_losing_the_lease_stops_training_and_nothing_is_reported():
    api = FakeApi(assignment(1, heartbeat_interval=0.01, lease=1.0),
                  heartbeat_error=AttemptRejected(1, "ATTEMPT_NOT_CURRENT", "RUNNING", "reassigned"))
    started = time.monotonic()
    worker, _ = make_worker(api, train=train_until_stopped)

    worker.run()

    assert api.completed == [] and api.failures == []
    assert time.monotonic() - started < 2  # stopped by the lost lease, not by running out the loop


def test_an_unreachable_api_for_a_full_lease_stops_training():
    api = FakeApi(assignment(1, heartbeat_interval=0.01, lease=0.1), heartbeat_error=ApiUnavailable("down"))
    started = time.monotonic()
    worker, _ = make_worker(api, train=train_until_stopped)

    worker.run()

    assert api.completed == []
    assert api.heartbeats >= 2
    assert time.monotonic() - started < 2  # training itself stopped; nothing ran to completion
