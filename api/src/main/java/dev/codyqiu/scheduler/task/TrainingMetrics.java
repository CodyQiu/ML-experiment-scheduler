package dev.codyqiu.scheduler.task;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Metrics a worker reports for a successful {@link Task#SYNTHETIC_MLP_V1} run.
 *
 * <p>{@code valAccuracy} is the fraction of the fixed validation split that the final model
 * classifies correctly. It is the ranking metric (larger is better). The upper bounds also reject
 * non-finite values: JSON cannot express NaN, but an overflowing literal such as {@code 1e400}
 * parses as infinity.
 */
public record TrainingMetrics(
		@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double valAccuracy,
		@NotNull @DecimalMin("0.0") @DecimalMax("1000000") Double valLoss,
		@NotNull @DecimalMin("0.0") @DecimalMax("1000000") Double trainLoss,
		@NotNull @DecimalMin("0.0") @DecimalMax("86400") Double trainingSeconds) {
}
