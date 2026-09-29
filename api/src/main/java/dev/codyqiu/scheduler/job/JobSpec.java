package dev.codyqiu.scheduler.job;

import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** One job to create: a validated configuration and the seed it runs with. */
public record JobSpec(
		@NotNull @Min(0) Integer seed,
		@NotNull @Valid SyntheticMlpConfig config) {
}
