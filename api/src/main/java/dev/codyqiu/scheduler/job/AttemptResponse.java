package dev.codyqiu.scheduler.job;

import java.time.Instant;

/**
 * One execution of a job, for the history view. The attempt id is left out on purpose: while an
 * attempt runs, its id is the worker's credential.
 */
public record AttemptResponse(
		int attemptNumber,
		String workerId,
		AttemptStatus status,
		Instant claimedAt,
		Instant lastHeartbeatAt,
		Instant finishedAt,
		String errorType,
		String errorMessage,
		Boolean retryable) {
}
