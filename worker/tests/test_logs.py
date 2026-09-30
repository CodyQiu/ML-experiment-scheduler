"""JSON log lines: the shape of the API's lines, with each event's fields at the top level."""

import io
import json
import logging
import re
from uuid import uuid4

import pytest

from scheduler_worker import logs


@pytest.fixture
def logger():
    """A logger whose JSON output the test can read back, line by line."""
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logs.JsonFormatter({"service.name": "scheduler-worker", "workerId": "w-1"}))
    log = logging.getLogger("test.logs")
    log.addHandler(handler)
    log.setLevel(logging.INFO)
    log.propagate = False
    log.lines = lambda: stream.getvalue().splitlines()
    yield log
    log.removeHandler(handler)


def test_a_line_has_the_api_shape_with_the_event_fields_at_the_top_level(logger):
    attempt_id = uuid4()

    logger.warning("job=%d lease lost", 7, extra={"event.action": "lease.lost", "jobId": 7, "attemptId": attempt_id,
                                                  "retryable": True})

    [text] = logger.lines()
    line = json.loads(text)
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z", line["@timestamp"])
    assert line["log"] == {"level": "WARN", "logger": "test.logs"}  # Logback's name for WARNING
    assert line["service"] == {"name": "scheduler-worker"}
    assert line["workerId"] == "w-1"
    assert line["message"] == "job=7 lease lost"
    assert line["event"] == {"action": "lease.lost"}  # dotted names nest, as in the API's lines
    assert line["jobId"] == 7 and line["retryable"] is True  # numbers and booleans keep their types
    assert line["attemptId"] == str(attempt_id)
    assert "error" not in line


def test_an_exception_becomes_error_fields_on_the_same_line(logger):
    try:
        raise ValueError("bad value")
    except ValueError:
        logger.exception("training raised an unexpected error", extra={"event.action": "training.error"})

    [text] = logger.lines()
    error = json.loads(text)["error"]
    assert error["type"] == "ValueError"
    assert error["message"] == "bad value"
    assert "Traceback" in error["stack_trace"] and "ValueError: bad value" in error["stack_trace"]


def test_an_attempt_log_prefixes_its_tag_and_adds_its_ids_to_each_calls_fields(logger):
    attempt = logs.AttemptLog(logger, "job=7 attempt=2", {"jobId": 7, "attemptNumber": 2})

    attempt.info("done: valAccuracy=%.2f", 0.9, extra={"event.action": "result.accepted", "valAccuracy": 0.9})

    line = json.loads(logger.lines()[0])
    assert line["message"] == "job=7 attempt=2 done: valAccuracy=0.90"
    assert (line["jobId"], line["attemptNumber"], line["valAccuracy"]) == (7, 2, 0.9)
    assert line["event"] == {"action": "result.accepted"}


def test_an_unknown_format_is_rejected():
    with pytest.raises(ValueError, match="LOG_FORMAT"):
        logs.configure("xml", {})
