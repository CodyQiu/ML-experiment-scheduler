package dev.codyqiu.scheduler.worker;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A worker's report that its attempt failed.
 *
 * <p>{@code retryable} is the worker's judgment. Transient trouble (an unexpected error, a shutdown)
 * is worth another attempt; a deterministic failure (a diverged run, a config it cannot execute)
 * is not. The job's attempt budget still has the last word.
 */
public record FailJobRequest(
		@NotNull UUID attemptId,
		@NotNull Boolean retryable,
		@NotNull
		@Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}", message = "must be an UPPER_SNAKE_CASE code of at most 64 characters")
		String errorType,
		@NotNull @Size(max = 2000) String message) {
}
