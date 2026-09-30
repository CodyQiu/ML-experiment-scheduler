"""The heartbeat thread against a fake client."""

import threading
import time
from uuid import uuid4

from scheduler_worker.client import ApiUnavailable, AttemptRejected
from scheduler_worker.heartbeat import Heartbeat


class FakeClient:
    """Answers heartbeats from a script of results (None means success) and counts them."""

    def __init__(self, *results, on_call=None):
        self.results = list(results)
        self.calls = 0
        self.on_call = on_call
        self.called = threading.Event()

    def heartbeat(self, job_id, attempt_id):
        self.calls += 1
        if self.on_call:
            self.on_call()
        self.called.set()
        result = self.results.pop(0) if self.results else None
        if isinstance(result, Exception):
            raise result


def heartbeat(client, interval=0.01, lease_seconds=5.0, clock=time.monotonic):
    return Heartbeat(client, 7, uuid4(), interval=interval, lease_seconds=lease_seconds, clock=clock)


def test_renews_the_lease_every_interval_until_the_block_ends():
    client = FakeClient()
    with heartbeat(client) as beat:
        time.sleep(0.1)
    calls_at_exit = client.calls
    time.sleep(0.05)

    assert calls_at_exit >= 3
    assert client.calls == calls_at_exit  # the thread stopped with the block
    assert not beat.lost.is_set()


def test_a_rejected_renewal_marks_the_lease_lost_and_stops_heartbeating():
    client = FakeClient(None, AttemptRejected(7, "LEASE_EXPIRED", "RUNNING", "expired"))
    with heartbeat(client) as beat:
        assert beat.lost.wait(2)
        calls_when_lost = client.calls
        time.sleep(0.05)

    assert "LEASE_EXPIRED" in beat.reason
    assert calls_when_lost == 2 and client.calls == 2


def test_transient_failures_shorter_than_a_lease_are_tolerated():
    client = FakeClient(ApiUnavailable("down"), ApiUnavailable("down"), None, None)
    with heartbeat(client, lease_seconds=5.0) as beat:
        time.sleep(0.1)

    assert not beat.lost.is_set()
    assert client.calls >= 4


def test_no_successful_renewal_for_a_whole_lease_means_the_lease_is_gone():
    # Each failed call advances a fake clock by 0.3 s. With a 1 s lease, the fourth failure is
    # 1.2 s after the last success: the lease has certainly expired, even with the API unreachable.
    now = [100.0]
    client = FakeClient(*[ApiUnavailable("down")] * 10, on_call=lambda: now.__setitem__(0, now[0] + 0.3))
    with heartbeat(client, lease_seconds=1.0, clock=lambda: now[0]) as beat:
        assert beat.lost.wait(2)

    assert client.calls == 4
    assert "no heartbeat succeeded" in beat.reason


def test_leaving_the_block_stops_the_thread_without_waiting_out_the_interval():
    client = FakeClient()
    started = time.monotonic()
    with heartbeat(client, interval=60.0):
        pass

    assert time.monotonic() - started < 1
    assert client.calls == 0
