package dev.codyqiu.scheduler.worker;

import java.util.UUID;

import dev.codyqiu.scheduler.task.TrainingMetrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CompleteJobRequest(
		@NotNull UUID attemptId,
		@NotNull @Valid TrainingMetrics metrics) {
}
