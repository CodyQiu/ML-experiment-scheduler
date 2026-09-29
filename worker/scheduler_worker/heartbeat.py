"""Keeps an attempt's lease alive while it runs, and notices when the attempt loses authority."""

import logging
import threading
import time
from collections.abc import Callable
from uuid import UUID

from .client import ApiClient, ApiError, ApiUnavailable, AttemptRejected

log = logging.getLogger(__name__)


class Heartbeat:
    """Renews the lease every `interval` seconds on a background thread, for as long as the `with`
    block runs.

    `lost` is set, and training stops at its next minibatch, when either of these happens:
    - The API rejects a renewal (409). The lease expired or the job was reassigned, so this
      attempt's authority is definitively gone.
    - No renewal has succeeded for a whole lease duration. The lease has then certainly expired by
      the server's clock as well. The last success was received after the server set that expiry,
      so the expiry lies at most `lease_seconds` after that receipt.

    The second check uses only elapsed time on this machine's monotonic clock and never compares
    clocks across machines. It is a shortcut that saves wasted training during an outage; the API
    would reject the result anyway.
    """

    def __init__(self, client: ApiClient, job_id: int, attempt_id: UUID, *, interval: float, lease_seconds: float,
                 clock: Callable[[], float] = time.monotonic):
        self._client = client
        self._job_id = job_id
        self._attempt_id = attempt_id
        self._interval = interval
        self._lease_seconds = lease_seconds
        self._clock = clock
        self._stop = threading.Event()
        self.lost = threading.Event()
        self.reason: str | None = None
        # The claim response that granted the first lease was received just before this point.
        self._last_renewal = clock()
        self._thread = threading.Thread(target=self._run, name=f"heartbeat-job-{job_id}", daemon=True)

    def __enter__(self) -> "Heartbeat":
        self._thread.start()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self._stop.set()
        self._thread.join()

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                self._client.heartbeat(self._job_id, self._attempt_id)
            except AttemptRejected as exc:
                self._lose(f"the API rejected the lease ({exc.code}; job is {exc.job_state})")
                return
            except (ApiUnavailable, ApiError) as exc:
                silent_for = self._clock() - self._last_renewal
                if silent_for >= self._lease_seconds:
                    self._lose(f"no heartbeat succeeded for {silent_for:.1f}s, a full lease")
                    return
                log.warning("job=%d heartbeat failed (%s); retrying in %.1fs", self._job_id, exc, self._interval)
                continue
            self._last_renewal = self._clock()

    def _lose(self, reason: str) -> None:
        self.reason = reason
        self.lost.set()
