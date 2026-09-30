"""Validated inputs: the job a worker is handed, and the worker's own settings.

The API is the gatekeeper for job configs. The worker checks them again anyway, so that version
skew between the API and a worker fails loudly instead of training something nobody asked for.
"""

import math
import os
import re
import socket
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from . import logs

TASK_ID = "synthetic-mlp-v1"
OPTIMIZERS = ("sgd", "adam")
WORKER_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,64}")
CONFIG_FIELDS = frozenset(
    {"learningRate", "hiddenUnits", "hiddenLayers", "batchSize", "epochs", "optimizer", "weightDecay"})


class InvalidAssignment(ValueError):
    """The API handed out a job this worker cannot run as specified."""


@dataclass(frozen=True)
class MlpConfig:
    """Hyperparameters of task synthetic-mlp-v1, with the same bounds the API enforces."""

    learning_rate: float
    hidden_units: int
    hidden_layers: int
    batch_size: int
    epochs: int
    optimizer: str
    weight_decay: float

    @classmethod
    def from_json(cls, data: Any) -> "MlpConfig":
        if not isinstance(data, Mapping):
            raise InvalidAssignment("config must be an object")
        missing, unknown = CONFIG_FIELDS - data.keys(), data.keys() - CONFIG_FIELDS
        if missing or unknown:
            raise InvalidAssignment(f"config has missing fields {sorted(missing)} and unknown fields {sorted(unknown)}")
        if data["optimizer"] not in OPTIMIZERS:
            raise InvalidAssignment(f"optimizer must be one of {list(OPTIMIZERS)}, got {data['optimizer']!r}")
        return cls(
            learning_rate=_number(data, "learningRate", 0.00001, 1.0),
            hidden_units=_integer(data, "hiddenUnits", 1, 256),
            hidden_layers=_integer(data, "hiddenLayers", 1, 4),
            batch_size=_integer(data, "batchSize", 8, 1024),
            epochs=_integer(data, "epochs", 1, 100),
            optimizer=data["optimizer"],
            weight_decay=_number(data, "weightDecay", 0.0, 0.1),
        )


@dataclass(frozen=True)
class Assignment:
    """A claimed job. attempt_id is this worker's credential for reporting on it."""

    job_id: int
    attempt_id: UUID
    attempt_number: int
    experiment_id: int
    seed: int
    config: MlpConfig
    lease_seconds: float
    heartbeat_interval_seconds: float

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> "Assignment":
        if data.get("task") != TASK_ID:
            raise InvalidAssignment(f"unsupported task {data.get('task')!r}; this worker runs {TASK_ID!r}")
        try:
            attempt_id = UUID(data["attemptId"])
        except (KeyError, TypeError, ValueError) as exc:
            raise InvalidAssignment(f"attemptId must be a UUID, got {data.get('attemptId')!r}") from exc
        lease_seconds = _number(data, "leaseSeconds", 0.001, 3600.0)
        heartbeat_interval = _number(data, "heartbeatIntervalSeconds", 0.001, 3600.0)
        if heartbeat_interval >= lease_seconds:
            raise InvalidAssignment(
                f"heartbeatIntervalSeconds ({heartbeat_interval}) must be shorter than leaseSeconds ({lease_seconds})")
        return cls(
            job_id=_integer(data, "jobId", 1, None),
            attempt_id=attempt_id,
            attempt_number=_integer(data, "attemptNumber", 1, None),
            experiment_id=_integer(data, "experimentId", 1, None),
            seed=_integer(data, "seed", 0, 2**31 - 1),
            config=MlpConfig.from_json(data.get("config")),
            lease_seconds=lease_seconds,
            heartbeat_interval_seconds=heartbeat_interval,
        )


def identify(data: Mapping[str, Any]) -> tuple[int, UUID] | None:
    """The job and attempt an assignment is about, if those two fields are usable. That is enough
    to report a failure even when the rest of the assignment cannot be run."""
    try:
        return _integer(data, "jobId", 1, None), UUID(data["attemptId"])
    except (InvalidAssignment, KeyError, TypeError, ValueError):
        return None


@dataclass(frozen=True)
class WorkerSettings:
    api_url: str
    worker_id: str
    poll_initial_seconds: float
    poll_max_seconds: float
    connect_timeout_seconds: float
    read_timeout_seconds: float
    report_attempts: int
    torch_threads: int
    log_format: str = "text"

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "WorkerSettings":
        worker_id = env.get("WORKER_ID") or default_worker_id()
        if not WORKER_ID_PATTERN.fullmatch(worker_id):
            raise ValueError(f"WORKER_ID must be 1-64 of [A-Za-z0-9._-], got {worker_id!r}")
        return cls(
            api_url=env.get("SCHEDULER_API_URL", "http://localhost:8080").rstrip("/"),
            worker_id=worker_id,
            poll_initial_seconds=_env_float(env, "POLL_INITIAL_SECONDS", 0.5),
            poll_max_seconds=_env_float(env, "POLL_MAX_SECONDS", 5.0),
            connect_timeout_seconds=_env_float(env, "HTTP_CONNECT_TIMEOUT_SECONDS", 3.0),
            read_timeout_seconds=_env_float(env, "HTTP_READ_TIMEOUT_SECONDS", 10.0),
            report_attempts=_env_int(env, "REPORT_ATTEMPTS", 5),
            torch_threads=_env_int(env, "TORCH_NUM_THREADS", 1),
            log_format=_env_choice(env, "LOG_FORMAT", logs.FORMATS),
        )


def default_worker_id() -> str:
    """hostname-pid, restricted to the characters the API accepts. In a container the hostname is
    the container id, so replicas get distinct ids."""
    raw = f"{socket.gethostname()}-{os.getpid()}"
    return re.sub(r"[^A-Za-z0-9._-]", "-", raw)[-64:]


def _integer(data: Mapping[str, Any], key: str, low: int, high: int | None) -> int:
    value = data.get(key)
    # bool is a subclass of int in Python, so True would otherwise pass as 1.
    if isinstance(value, bool) or not isinstance(value, int):
        raise InvalidAssignment(f"{key} must be an integer, got {value!r}")
    if value < low or (high is not None and value > high):
        raise InvalidAssignment(f"{key} must be between {low} and {high}, got {value}")
    return value


def _number(data: Mapping[str, Any], key: str, low: float, high: float) -> float:
    value = data.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise InvalidAssignment(f"{key} must be a finite number, got {value!r}")
    if not low <= value <= high:
        raise InvalidAssignment(f"{key} must be between {low} and {high}, got {value}")
    return float(value)


def _env_choice(env: Mapping[str, str], key: str, choices: tuple[str, ...]) -> str:
    """The variable's value, which must be one of `choices`; the first choice is the default."""
    value = env.get(key, choices[0])
    if value not in choices:
        raise ValueError(f"{key} must be one of {list(choices)}, got {value!r}")
    return value


def _env_float(env: Mapping[str, str], key: str, default: float) -> float:
    value = float(env.get(key, default))
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{key} must be a positive number, got {value}")
    return value


def _env_int(env: Mapping[str, str], key: str, default: int) -> int:
    value = int(env.get(key, default))
    if value < 1:
        raise ValueError(f"{key} must be at least 1, got {value}")
    return value
