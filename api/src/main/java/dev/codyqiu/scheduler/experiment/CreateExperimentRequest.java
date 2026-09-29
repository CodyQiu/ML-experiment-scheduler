package dev.codyqiu.scheduler.experiment;

import java.util.List;

import dev.codyqiu.scheduler.job.JobSpec;
import dev.codyqiu.scheduler.task.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /experiments}. The whole request is validated before any row is written,
 * so an invalid job anywhere in the batch rejects the entire batch.
 */
public record CreateExperimentRequest(
		@NotBlank @Size(max = MAX_NAME_LENGTH) String name,
		@NotNull Task task,
		@Min(1) @Max(MAX_ATTEMPTS_LIMIT) Integer maxAttempts,
		@NotNull @Size(min = 1, max = MAX_JOBS) List<@NotNull @Valid JobSpec> jobs) {

	public static final int MAX_NAME_LENGTH = 200;

	public static final int MAX_JOBS = 500;

	public static final int MAX_ATTEMPTS_LIMIT = 10;

	public static final int DEFAULT_MAX_ATTEMPTS = 3;

	/** Total executions allowed per job, including the first. */
	public int effectiveMaxAttempts() {
		return (maxAttempts != null) ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
	}

}
