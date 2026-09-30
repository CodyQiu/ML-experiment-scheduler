import random
from collections.abc import Callable


class Backoff:
    """Exponential backoff with jitter: delays double from `initial` up to `maximum`.

    Each delay is drawn uniformly from [ceiling / 2, ceiling]. The randomness keeps workers that
    failed at the same moment from retrying in lockstep. The floor at half the ceiling keeps any
    single wait from shrinking to almost nothing, which would turn polling into a busy loop.
    """

    def __init__(self, initial: float, maximum: float, rand: Callable[[], float] = random.random):
        self._initial = initial
        self._maximum = maximum
        self._rand = rand
        self._failures = 0

    def next(self) -> float:
        # The exponent is capped: after hours of idle polling, 2 ** failures would overflow a float.
        ceiling = min(self._maximum, self._initial * 2 ** min(self._failures, 32))
        self._failures += 1
        return ceiling / 2 + self._rand() * ceiling / 2

    def reset(self) -> None:
        self._failures = 0
