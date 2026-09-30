"""Task synthetic-mlp-v1: a small MLP on a fixed three-class spiral dataset.

Everything random is seeded explicitly. The dataset uses a constant seed, so every job sees the
same data. The job's seed drives weight initialization and minibatch order. Training runs on one
CPU thread with deterministic algorithms, so the same config and seed give bit-identical metrics
on the same PyTorch build and CPU architecture. A different PyTorch version or architecture may
change the last bits.
"""

import math
import time
from collections.abc import Callable
from dataclasses import dataclass
from functools import cache

import torch
from torch import nn

from .config import MlpConfig

# Fixed for the lifetime of task id synthetic-mlp-v1. Changing any of these makes a new task.
DATASET_SEED = 20260929
CLASSES = 3
POINTS_PER_CLASS = 1000
ANGLE_NOISE = 0.2
TRAIN_POINTS = 2000


class TrainingStopped(Exception):
    """Training was asked to stop before it finished. The partial result must not be reported."""


class TrainingDiverged(Exception):
    """A loss became NaN or infinite."""


@dataclass(frozen=True)
class Dataset:
    train_x: torch.Tensor
    train_y: torch.Tensor
    val_x: torch.Tensor
    val_y: torch.Tensor


@dataclass(frozen=True)
class Metrics:
    """valAccuracy: fraction of the validation split the final model classifies correctly.
    valLoss / trainLoss: mean cross-entropy of the final model on each split.
    trainingSeconds: wall-clock time of the training epochs only (no data setup or evaluation).
    """

    val_accuracy: float
    val_loss: float
    train_loss: float
    training_seconds: float

    def to_json(self) -> dict[str, float]:
        return {
            "valAccuracy": self.val_accuracy,
            "valLoss": self.val_loss,
            "trainLoss": self.train_loss,
            "trainingSeconds": self.training_seconds,
        }


def configure_torch(threads: int) -> None:
    """Single-threaded, deterministic CPU execution. Call once at startup, before any training."""
    torch.set_num_threads(threads)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)


@cache
def make_dataset() -> Dataset:
    """Three interleaved spiral arms in 2-D with noisy angles, split 2000 train / 1000 validation.

    Generated once per process. It has its own generator, so it never disturbs the job's seeds.
    """
    generator = torch.Generator().manual_seed(DATASET_SEED)
    points, labels = [], []
    radius = torch.linspace(0.0, 1.0, POINTS_PER_CLASS)
    for label in range(CLASSES):
        angle = torch.linspace(label * 4.0, (label + 1) * 4.0, POINTS_PER_CLASS)
        angle = angle + ANGLE_NOISE * torch.randn(POINTS_PER_CLASS, generator=generator)
        points.append(torch.stack([radius * torch.sin(angle), radius * torch.cos(angle)], dim=1))
        labels.append(torch.full((POINTS_PER_CLASS,), label, dtype=torch.long))
    order = torch.randperm(CLASSES * POINTS_PER_CLASS, generator=generator)
    x, y = torch.cat(points)[order], torch.cat(labels)[order]
    return Dataset(x[:TRAIN_POINTS], y[:TRAIN_POINTS], x[TRAIN_POINTS:], y[TRAIN_POINTS:])


def build_model(config: MlpConfig) -> nn.Sequential:
    layers: list[nn.Module] = []
    width = 2
    for _ in range(config.hidden_layers):
        layers += [nn.Linear(width, config.hidden_units), nn.ReLU()]
        width = config.hidden_units
    layers.append(nn.Linear(width, CLASSES))
    return nn.Sequential(*layers)


def train(config: MlpConfig, seed: int, should_stop: Callable[[], bool] = lambda: False) -> Metrics:
    """Trains one model and evaluates it.

    should_stop is checked before every minibatch, so a stop request takes effect within
    milliseconds and raises TrainingStopped.
    """
    data = make_dataset()
    torch.manual_seed(seed)  # weight initialization
    model = build_model(config)
    optimizer_type = torch.optim.Adam if config.optimizer == "adam" else torch.optim.SGD
    optimizer = optimizer_type(model.parameters(), lr=config.learning_rate, weight_decay=config.weight_decay)
    loss_fn = nn.CrossEntropyLoss()
    shuffle = torch.Generator().manual_seed(seed)  # minibatch order

    started = time.perf_counter()
    model.train()
    for epoch in range(1, config.epochs + 1):
        order = torch.randperm(TRAIN_POINTS, generator=shuffle)
        for start in range(0, TRAIN_POINTS, config.batch_size):
            if should_stop():
                raise TrainingStopped(f"stopped during epoch {epoch}")
            batch = order[start:start + config.batch_size]
            optimizer.zero_grad(set_to_none=True)
            loss = loss_fn(model(data.train_x[batch]), data.train_y[batch])
            loss.backward()
            optimizer.step()
        require_finite(f"training loss in epoch {epoch}", loss.item())
    training_seconds = time.perf_counter() - started

    val_loss, val_accuracy = evaluate(model, data.val_x, data.val_y)
    train_loss, _ = evaluate(model, data.train_x, data.train_y)
    require_finite("validation loss", val_loss)
    require_finite("final training loss", train_loss)
    return Metrics(val_accuracy, val_loss, train_loss, training_seconds)


@torch.no_grad()
def evaluate(model: nn.Module, x: torch.Tensor, y: torch.Tensor) -> tuple[float, float]:
    """Mean cross-entropy and accuracy of the model on (x, y)."""
    model.eval()
    logits = model(x)
    loss = nn.functional.cross_entropy(logits, y).item()
    accuracy = (logits.argmax(dim=1) == y).double().mean().item()
    return loss, accuracy


def require_finite(what: str, value: float) -> None:
    if not math.isfinite(value):
        raise TrainingDiverged(f"{what} is {value}")
