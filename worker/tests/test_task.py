import dataclasses
import math

import pytest
import torch

from scheduler_worker import task
from scheduler_worker.config import MlpConfig

FAST = MlpConfig(learning_rate=0.01, hidden_units=32, hidden_layers=2, batch_size=64, epochs=5,
                 optimizer="adam", weight_decay=0.0)


def test_dataset_is_fixed_balanced_and_split_train_validation():
    task.make_dataset.cache_clear()
    first = task.make_dataset()
    task.make_dataset.cache_clear()
    second = task.make_dataset()

    for field in dataclasses.fields(task.Dataset):
        assert torch.equal(getattr(first, field.name), getattr(second, field.name))
    assert first.train_x.shape == (2000, 2)
    assert first.val_x.shape == (1000, 2)
    labels = torch.cat([first.train_y, first.val_y])
    assert torch.bincount(labels).tolist() == [1000, 1000, 1000]


def test_same_config_and_seed_reproduce_bit_identical_metrics():
    first = task.train(FAST, seed=7)
    second = task.train(FAST, seed=7)

    assert (first.val_accuracy, first.val_loss, first.train_loss) == (
        second.val_accuracy, second.val_loss, second.train_loss)


def test_seed_changes_initialization_and_batch_order():
    assert task.train(FAST, seed=7).val_loss != task.train(FAST, seed=8).val_loss


def test_metrics_are_well_formed_and_named_like_the_api():
    metrics = task.train(FAST, seed=1)

    assert 0.0 <= metrics.val_accuracy <= 1.0
    assert all(math.isfinite(value) and value >= 0 for value in (metrics.val_loss, metrics.train_loss))
    assert metrics.training_seconds > 0
    assert set(metrics.to_json()) == {"valAccuracy", "valLoss", "trainLoss", "trainingSeconds"}


def test_hyperparameters_matter_on_this_task():
    capable = MlpConfig(learning_rate=0.01, hidden_units=64, hidden_layers=2, batch_size=64, epochs=20,
                        optimizer="adam", weight_decay=0.0)
    crippled = MlpConfig(learning_rate=0.00001, hidden_units=8, hidden_layers=1, batch_size=64, epochs=2,
                         optimizer="sgd", weight_decay=0.0)

    assert task.train(capable, seed=0).val_accuracy > 0.95
    assert task.train(crippled, seed=0).val_accuracy < 0.5


def test_training_stops_at_the_next_minibatch_when_asked():
    checks = 0

    def should_stop() -> bool:
        nonlocal checks
        checks += 1
        return checks > 3

    with pytest.raises(task.TrainingStopped):
        task.train(FAST, seed=0, should_stop=should_stop)
    assert checks == 4


@pytest.mark.parametrize("value", [math.nan, math.inf, -math.inf])
def test_non_finite_losses_count_as_divergence(value):
    with pytest.raises(task.TrainingDiverged):
        task.require_finite("validation loss", value)
