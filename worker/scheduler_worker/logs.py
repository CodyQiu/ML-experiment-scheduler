"""Log output: human-readable text by default, or one JSON object per line (LOG_FORMAT=json).

JSON lines have the shape of the API's, which is Spring Boot's Elastic Common Schema format. One
query can then follow a job across the API and every worker:
- `@timestamp`, `log.level`, `log.logger`, `service.name`, `message`;
- the event's own fields at the top level: `event.action`, `jobId`, `attemptId`, and so on.

Call sites pass the fields with `extra=`, and dotted names nest, as they do in the API's lines.
"""

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

FORMATS = ("text", "json")
TEXT_FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"

# A LogRecord's own attributes. Anything else on a record came from extra= and is an event field.
_RECORD_ATTRIBUTES = frozenset(vars(logging.makeLogRecord({}))) | {"message", "asctime"}
# The level names of Logback, which the API's lines carry.
_LEVELS = {"WARNING": "WARN", "CRITICAL": "ERROR"}


class JsonFormatter(logging.Formatter):
    """Formats each record as one line of JSON. `fields` are added to every line."""

    def __init__(self, fields: dict[str, Any]):
        super().__init__()
        self._fields = fields

    def format(self, record: logging.LogRecord) -> str:
        line: dict[str, Any] = {
            "@timestamp": datetime.fromtimestamp(record.created, UTC).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "log": {"level": _LEVELS.get(record.levelname, record.levelname), "logger": record.name},
            "process": {"pid": record.process, "thread": {"name": record.threadName}},
            "message": record.getMessage(),
        }
        for key, value in self._fields.items():
            _put(line, key, value)
        for key, value in vars(record).items():
            if key not in _RECORD_ATTRIBUTES:
                _put(line, key, value)
        if record.exc_info and record.exc_info[0] is not None:
            error_type, error, _ = record.exc_info
            line["error"] = {
                "type": _qualified_name(error_type),
                "message": str(error),
                "stack_trace": self.formatException(record.exc_info),
            }
        line["ecs"] = {"version": "8.11"}
        # default=str writes UUIDs and other values as strings, like the API's encoder does.
        return json.dumps(line, default=str, ensure_ascii=False, separators=(",", ":"))


class AttemptLog(logging.LoggerAdapter):
    """Logs about one attempt. The text starts with its tag, e.g. "job=7 attempt=2", and JSON lines
    carry its ids as fields. Fields given to a single call are merged in."""

    def __init__(self, logger: logging.Logger, tag: str, fields: dict[str, Any]):
        super().__init__(logger, fields, merge_extra=True)
        self._tag = tag

    def process(self, msg: Any, kwargs: Any) -> tuple[Any, Any]:
        msg, kwargs = super().process(msg, kwargs)
        return f"{self._tag} {msg}", kwargs


def configure(log_format: str, fields: dict[str, Any]) -> None:
    """Sends INFO and above to stderr in the given format. `fields` go on every JSON line."""
    if log_format not in FORMATS:
        raise ValueError(f"LOG_FORMAT must be one of {list(FORMATS)}, got {log_format!r}")
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(JsonFormatter(fields) if log_format == "json" else logging.Formatter(TEXT_FORMAT))
    logging.basicConfig(level=logging.INFO, handlers=[handler], force=True)


def _put(line: dict[str, Any], key: str, value: Any) -> None:
    *parents, name = key.split(".")
    for parent in parents:
        line = line.setdefault(parent, {})
    line[name] = value


def _qualified_name(cls: type) -> str:
    return cls.__qualname__ if cls.__module__ == "builtins" else f"{cls.__module__}.{cls.__qualname__}"
