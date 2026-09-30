import copy
import math
import re
from uuid import UUID

import pytest

from scheduler_worker.config import (Assignment, InvalidAssignment, MlpConfig, WorkerSettings, default_worker_id,
                                     identify)

ASSIGNMENT = {
    "jobId": 17,
    "attemptId": "8b64bfc6-4e60-470c-94aa-234862d4287e",
    "attemptNumber": 1,
    "experimentId": 2,
    "task": "synthetic-mlp-v1",
    "seed": 3,
    "leaseExpiresAt": "2026-09-29T16:00:30Z",
    "leaseSeconds": 30.0,
    "heartbeatIntervalSeconds": 10.0,
    "config": {"learningRate": 1.0E-4, "hiddenUnits": 16, "hiddenLayers": 1, "batchSize": 64, "epochs": 20,
               "optimizer": "adam", "weightDecay": 0.0},
}


def with_config(**overrides):
    assignment = copy.deepcopy(ASSIGNMENT)
    assignment["config"].update(overrides)
    return assignment


def test_parses_an_assignment_as_the_api_sends_it():
    assignment = Assignment.from_json(ASSIGNMENT)

    assert assignment.job_id == 17
    assert assignment.attempt_id == UUID("8b64bfc6-4e60-470c-94aa-234862d4287e")
    assert assignment.seed == 3
    assert (assignment.lease_seconds, assignment.heartbeat_interval_seconds) == (30.0, 10.0)
    assert assignment.config == MlpConfig(learning_rate=0.0001, hidden_units=16, hidden_layers=1, batch_size=64,
                                          epochs=20, optimizer="adam", weight_decay=0.0)


@pytest.mark.parametrize("overrides, message", [
    ({"hiddenUnits": True}, "hiddenUnits must be an integer"),
    ({"epochs": 20.5}, "epochs must be an integer"),
    ({"hiddenLayers": 5}, "hiddenLayers must be between 1 and 4"),
    ({"learningRate": math.nan}, "learningRate must be a finite number"),
    ({"learningRate": 2.0}, "learningRate must be between"),
    ({"optimizer": "adamw"}, "optimizer must be one of"),
    ({"dropout": 0.5}, "unknown fields ['dropout']"),
])
def test_rejects_configs_the_api_should_never_have_sent(overrides, message):
    with pytest.raises(InvalidAssignment, match=re.escape(message)):
        Assignment.from_json(with_config(**overrides))


def test_rejects_missing_config_fields():
    assignment = copy.deepcopy(ASSIGNMENT)
    del assignment["config"]["epochs"]

    with pytest.raises(InvalidAssignment, match="missing fields"):
        Assignment.from_json(assignment)


def test_rejects_tasks_this_worker_cannot_run():
    with pytest.raises(InvalidAssignment, match="unsupported task 'mnist'"):
        Assignment.from_json({**ASSIGNMENT, "task": "mnist"})


def test_rejects_a_malformed_attempt_id():
    with pytest.raises(InvalidAssignment, match="attemptId must be a UUID"):
        Assignment.from_json({**ASSIGNMENT, "attemptId": "nope"})


def test_settings_come_from_the_environment():
    settings = WorkerSettings.from_env({"SCHEDULER_API_URL": "http://api:8080/", "WORKER_ID": "w-1",
                                        "POLL_MAX_SECONDS": "2", "REPORT_ATTEMPTS": "3"})

    assert settings.api_url == "http://api:8080"
    assert settings.worker_id == "w-1"
    assert settings.poll_max_seconds == 2.0
    assert settings.report_attempts == 3
    assert settings.torch_threads == 1
    assert settings.log_format == "text"
    assert WorkerSettings.from_env({"LOG_FORMAT": "json"}).log_format == "json"


def test_default_worker_id_is_accepted_by_the_api():
    assert WorkerSettings.from_env({}).worker_id == default_worker_id()


@pytest.mark.parametrize("env", [{"WORKER_ID": "has space"}, {"POLL_MAX_SECONDS": "0"}, {"TORCH_NUM_THREADS": "0"},
                                 {"LOG_FORMAT": "xml"}])
def test_rejects_invalid_settings(env):
    with pytest.raises(ValueError):
        WorkerSettings.from_env(env)


@pytest.mark.parametrize("overrides, message", [
    ({"heartbeatIntervalSeconds": 30.0}, "must be shorter than leaseSeconds"),
    ({"leaseSeconds": 0}, "leaseSeconds must be between"),
    ({"heartbeatIntervalSeconds": None}, "heartbeatIntervalSeconds must be a finite number"),
])
def test_rejects_a_lease_policy_it_cannot_follow(overrides, message):
    with pytest.raises(InvalidAssignment, match=re.escape(message)):
        Assignment.from_json({**ASSIGNMENT, **overrides})


def test_identifies_the_job_and_attempt_even_when_the_rest_is_unusable():
    assert identify({**ASSIGNMENT, "task": "mnist", "config": None}) == (17, UUID(ASSIGNMENT["attemptId"]))
    assert identify({**ASSIGNMENT, "attemptId": "nope"}) is None
    assert identify({"attemptId": ASSIGNMENT["attemptId"]}) is None
