package dev.codyqiu.scheduler.job;

import java.time.Instant;

import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.TrainingMetrics;

/**
 * A job as exposed to researchers. The attempt identity is deliberately absent: it is the
 * worker's credential for changing the job, not something a read API should hand out.
 */
public record JobResponse(
		long id,
		long experimentId,
		int jobIndex,
		int seed,
		SyntheticMlpConfig config,
		JobState state,
		int attemptCount,
		int maxAttempts,
		String workerId,
		Double valAccuracy,
		TrainingMetrics result,
		Instant createdAt,
		Instant startedAt,
		Instant finishedAt,
		Instant leaseExpiresAt,
		JobError lastError) {
}
