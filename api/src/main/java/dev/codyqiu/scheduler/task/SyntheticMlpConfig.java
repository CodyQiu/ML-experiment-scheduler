package dev.codyqiu.scheduler.task;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Hyperparameters for {@link Task#SYNTHETIC_MLP_V1}.
 *
 * <p>Every field is required, so a stored config fully describes its run and never depends on a
 * default that could change later. The bounds keep each job small enough for a CPU worker.
 */
public record SyntheticMlpConfig(
		@NotNull @DecimalMin("0.00001") @DecimalMax("1.0") Double learningRate,
		@NotNull @Min(1) @Max(256) Integer hiddenUnits,
		@NotNull @Min(1) @Max(4) Integer hiddenLayers,
		@NotNull @Min(8) @Max(1024) Integer batchSize,
		@NotNull @Min(1) @Max(100) Integer epochs,
		@NotNull Optimizer optimizer,
		@NotNull @DecimalMin("0.0") @DecimalMax("0.1") Double weightDecay) {
}
