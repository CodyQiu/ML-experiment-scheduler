package dev.codyqiu.scheduler.job;

import java.time.Instant;
import java.util.UUID;

import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.Task;

/**
 * A claimed job, as handed to the worker that claimed it. {@code attemptId} is that worker's
 * credential: every later request about this execution must present it.
 *
 * <p>Workers pace heartbeats by {@code heartbeatIntervalSeconds} and measure elapsed time on their
 * own clock. {@code leaseExpiresAt} is database time and only informational: a worker must never
 * compare it with its own clock.
 */
public record JobAssignment(
		long jobId,
		UUID attemptId,
		int attemptNumber,
		long experimentId,
		Task task,
		int seed,
		SyntheticMlpConfig config,
		Instant leaseExpiresAt,
		double leaseSeconds,
		double heartbeatIntervalSeconds) {
}
