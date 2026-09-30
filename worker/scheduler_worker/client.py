"""HTTP client for the worker protocol, with explicit timeouts and bounded, jittered retries.

Responses fall into three kinds:
- Definitive answers (2xx, 4xx) are returned or raised immediately and never retried.
- Transient failures (connection errors, timeouts, 5xx) mean "no answer": the request may or may
  not have taken effect. Only requests that are safe to repeat are retried.
- A 409 means the API refused this attempt. The caller must stop and discard its work.
"""

import logging
import time
from collections.abc import Callable
from typing import Any
from uuid import UUID

import requests

from .backoff import Backoff

log = logging.getLogger(__name__)

MAX_ERROR_MESSAGE = 2000  # the API rejects longer failure messages


class ApiUnavailable(Exception):
    """No definitive answer: connection errors, timeouts, or 5xx responses, after any retries."""


class ApiError(Exception):
    """A definitive refusal other than 409 (e.g. 400 or 404). Signals a bug or version skew."""


class AttemptRejected(Exception):
    """The API refused this attempt (409). Stop working on it and discard the result.

    code is LEASE_EXPIRED (the lease ran out) or ATTEMPT_NOT_CURRENT (the job was reassigned or
    already finished).
    """

    def __init__(self, job_id: int, code: str | None, job_state: str | None, detail: str):
        super().__init__(detail)
        self.job_id = job_id
        self.code = code
        self.job_state = job_state


class ApiClient:
    def __init__(self, base_url: str, *, connect_timeout: float, read_timeout: float, report_attempts: int,
                 sleep: Callable[[float], None] = time.sleep, session: requests.Session | None = None):
        self._base_url = base_url.rstrip("/")
        self._timeout = (connect_timeout, read_timeout)
        self._report_attempts = report_attempts
        self._sleep = sleep
        self._session = session or requests.Session()

    def claim(self, worker_id: str) -> dict[str, Any] | None:
        """Claims the oldest queued job; returns None when nothing is queued.

        Deliberately not retried. A claim is not idempotent: if one committed but its response was
        lost, retrying would claim a second job while the first stays RUNNING under an attempt
        nobody holds. The worker loop simply polls again after a backoff.
        """
        response = self._post("/worker/jobs/claim", {"workerId": worker_id}, attempts=1)
        if response.status_code == 204:
            return None
        if response.status_code == 200:
            return response.json()
        raise ApiError(_describe(response))

    def complete(self, job_id: int, attempt_id: UUID, metrics: dict[str, float]) -> None:
        """Reports a successful result, retrying transient failures.

        Retrying is safe. The API accepts a result only from the job's running attempt, and only
        once, so a duplicate delivery can never overwrite anything.
        """
        body = {"attemptId": str(attempt_id), "metrics": metrics}
        response = self._post(f"/worker/jobs/{job_id}/complete", body, attempts=self._report_attempts)
        if response.status_code == 200:
            return
        if response.status_code == 409:
            raise _rejection(job_id, response)
        raise ApiError(_describe(response))

    def fail(self, job_id: int, attempt_id: UUID, *, retryable: bool, error_type: str, message: str) -> str:
        """Reports that the attempt failed; returns the job's new state (QUEUED or FAILED).

        Retried like a completion, and safe to retry for the same reason: only the running attempt
        can end the job, so a duplicate delivery changes nothing.
        """
        body = {"attemptId": str(attempt_id), "retryable": retryable, "errorType": error_type,
                "message": message[:MAX_ERROR_MESSAGE]}
        response = self._post(f"/worker/jobs/{job_id}/fail", body, attempts=self._report_attempts)
        if response.status_code == 200:
            return response.json()["state"]
        if response.status_code == 409:
            raise _rejection(job_id, response)
        raise ApiError(_describe(response))

    def heartbeat(self, job_id: int, attempt_id: UUID) -> None:
        """Renews the attempt's lease. Sent once: on a transient failure, the heartbeat thread tries
        again at its next interval. Raises AttemptRejected once the lease is lost for good."""
        response = self._post(f"/worker/jobs/{job_id}/heartbeat", {"attemptId": str(attempt_id)}, attempts=1)
        if response.status_code == 200:
            return
        if response.status_code == 409:
            raise _rejection(job_id, response)
        raise ApiError(_describe(response))

    def _post(self, path: str, body: dict[str, Any], *, attempts: int) -> requests.Response:
        backoff = Backoff(initial=0.5, maximum=8.0)
        for attempt in range(1, attempts + 1):
            try:
                response = self._session.post(self._base_url + path, json=body, timeout=self._timeout)
                if response.status_code < 500:
                    return response
                failure = f"HTTP {response.status_code}"
            except (requests.ConnectionError, requests.Timeout) as exc:
                failure = f"{type(exc).__name__}: {exc}"
            if attempt < attempts:
                delay = backoff.next()
                log.warning("POST %s attempt %d/%d failed (%s); retrying in %.1fs", path, attempt, attempts,
                            failure, delay)
                self._sleep(delay)
        raise ApiUnavailable(f"POST {path} failed after {attempts} attempt(s): {failure}")


def _rejection(job_id: int, response: requests.Response) -> AttemptRejected:
    problem = _problem(response)
    return AttemptRejected(job_id, problem.get("code"), problem.get("jobState"), problem.get("detail", "conflict"))


def _problem(response: requests.Response) -> dict[str, Any]:
    try:
        body = response.json()
    except ValueError:
        return {}
    return body if isinstance(body, dict) else {}


def _describe(response: requests.Response) -> str:
    problem = _problem(response)
    return f"HTTP {response.status_code} {problem.get('code', '')}: {problem.get('detail', response.text[:200])}"
