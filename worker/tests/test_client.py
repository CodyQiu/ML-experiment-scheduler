"""The client against a real HTTP server on localhost that replays scripted responses."""

import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

import pytest

from scheduler_worker.client import ApiClient, ApiError, ApiUnavailable, AttemptRejected

METRICS = {"valAccuracy": 0.9, "valLoss": 0.25, "trainLoss": 0.2, "trainingSeconds": 1.5}


class StubApi:
    """Each POST pops the next scripted (status, body, delay_seconds) and is recorded."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers["Content-Length"])
                stub.requests.append((self.path, json.loads(self.rfile.read(length))))
                status, body, delay = stub.responses.pop(0)
                time.sleep(delay)
                payload = json.dumps(body).encode() if body is not None else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        class Server(ThreadingHTTPServer):
            def handle_error(self, request, client_address):
                # A client that timed out closes its socket before the delayed reply is written;
                # that is the scenario under test, not an error.
                pass

        self.server = Server(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def stub_api():
    stubs = []

    def start(*responses):
        stubs.append(StubApi(*responses))
        return stubs[-1]

    yield start
    for stub in stubs:
        stub.close()


def client_for(url, sleeps, read_timeout=2.0):
    return ApiClient(url, connect_timeout=1.0, read_timeout=read_timeout, report_attempts=3, sleep=sleeps.append)


def test_claim_returns_the_assignment_or_none_when_nothing_is_queued(stub_api):
    api = stub_api((200, {"jobId": 1}, 0), (204, None, 0))
    client = client_for(api.url, [])

    assert client.claim("w-1") == {"jobId": 1}
    assert client.claim("w-1") is None
    assert api.requests[0] == ("/worker/jobs/claim", {"workerId": "w-1"})


def test_claim_is_never_retried(stub_api):
    api = stub_api((503, None, 0))
    sleeps = []

    with pytest.raises(ApiUnavailable):
        client_for(api.url, sleeps).claim("w-1")
    assert len(api.requests) == 1
    assert sleeps == []


def test_completion_is_retried_through_transient_failures(stub_api):
    api = stub_api((503, None, 0), (500, None, 0), (200, {"state": "SUCCEEDED"}, 0))
    sleeps = []
    attempt_id = uuid4()

    client_for(api.url, sleeps).complete(5, attempt_id, METRICS)

    assert [path for path, _ in api.requests] == ["/worker/jobs/5/complete"] * 3
    assert api.requests[0][1] == {"attemptId": str(attempt_id), "metrics": METRICS}
    assert len(sleeps) == 2 and 0.25 <= sleeps[0] <= 0.5 and 0.5 <= sleeps[1] <= 1.0


def test_completion_retries_are_bounded(stub_api):
    api = stub_api(*[(503, None, 0)] * 3)

    with pytest.raises(ApiUnavailable, match="after 3 attempt"):
        client_for(api.url, []).complete(5, uuid4(), METRICS)
    assert len(api.requests) == 3


def test_a_read_timeout_is_retried_like_any_transient_failure(stub_api):
    # The first response arrives after the client's read timeout: the report may or may not have
    # been recorded, so it is sent again. The API's guard makes the duplicate harmless.
    api = stub_api((200, {}, 0.5), (200, {}, 0))

    client_for(api.url, [], read_timeout=0.1).complete(5, uuid4(), METRICS)
    assert len(api.requests) == 2


def test_a_conflict_is_definitive_and_not_retried(stub_api):
    api = stub_api((409, {"code": "ATTEMPT_NOT_CURRENT", "jobState": "SUCCEEDED", "detail": "not running"}, 0))

    with pytest.raises(AttemptRejected) as rejected:
        client_for(api.url, []).complete(5, uuid4(), METRICS)
    assert (rejected.value.code, rejected.value.job_state) == ("ATTEMPT_NOT_CURRENT", "SUCCEEDED")
    assert len(api.requests) == 1


def test_other_client_errors_are_not_retried(stub_api):
    api = stub_api((400, {"code": "VALIDATION_FAILED", "detail": "bad"}, 0))

    with pytest.raises(ApiError, match="VALIDATION_FAILED"):
        client_for(api.url, []).complete(5, uuid4(), METRICS)
    assert len(api.requests) == 1


def test_an_unreachable_api_is_transient():
    with socket.socket() as probe:  # grab a free port, then close it so nothing listens there
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    sleeps = []

    with pytest.raises(ApiUnavailable, match="ConnectionError"):
        client_for(f"http://127.0.0.1:{port}", sleeps).complete(5, uuid4(), METRICS)
    assert len(sleeps) == 2


def test_heartbeat_renews_or_reports_a_lost_lease(stub_api):
    api = stub_api((200, {"jobId": 5, "leaseExpiresAt": "2026-09-29T16:00:30Z"}, 0),
                   (409, {"code": "LEASE_EXPIRED", "jobState": "RUNNING", "detail": "expired"}, 0))
    client = client_for(api.url, [])
    attempt_id = uuid4()

    client.heartbeat(5, attempt_id)
    with pytest.raises(AttemptRejected) as rejected:
        client.heartbeat(5, attempt_id)

    assert api.requests[0] == ("/worker/jobs/5/heartbeat", {"attemptId": str(attempt_id)})
    assert rejected.value.code == "LEASE_EXPIRED"


def test_a_failed_heartbeat_is_not_retried_by_the_client(stub_api):
    # The heartbeat thread tries again at its next interval instead.
    api = stub_api((503, None, 0))
    sleeps = []

    with pytest.raises(ApiUnavailable):
        client_for(api.url, sleeps).heartbeat(5, uuid4())
    assert len(api.requests) == 1
    assert sleeps == []


def test_a_failure_report_returns_the_new_state_and_is_retried_through_transient_errors(stub_api):
    api = stub_api((503, None, 0), (200, {"jobId": 5, "state": "QUEUED"}, 0))
    attempt_id = uuid4()

    state = client_for(api.url, []).fail(5, attempt_id, retryable=True, error_type="WORKER_ERROR", message="x" * 5000)

    assert state == "QUEUED"
    assert len(api.requests) == 2
    path, body = api.requests[0]
    assert path == "/worker/jobs/5/fail"
    assert (body["attemptId"], body["retryable"], body["errorType"]) == (str(attempt_id), True, "WORKER_ERROR")
    assert len(body["message"]) == 2000  # truncated to what the API accepts


def test_a_rejected_failure_report_raises(stub_api):
    api = stub_api((409, {"code": "LEASE_EXPIRED", "jobState": "RUNNING", "detail": "expired"}, 0))

    with pytest.raises(AttemptRejected) as rejected:
        client_for(api.url, []).fail(5, uuid4(), retryable=False, error_type="TRAINING_DIVERGED", message="nan")
    assert rejected.value.code == "LEASE_EXPIRED"
    assert len(api.requests) == 1
