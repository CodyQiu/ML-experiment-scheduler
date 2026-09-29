package dev.codyqiu.scheduler.lease;

import java.util.UUID;

import dev.codyqiu.scheduler.job.JobState;

/** A job whose expired attempt was ended; {@code newState} is QUEUED (retry) or FAILED (budget spent). */
public record RecoveredJob(long jobId, UUID expiredAttemptId, int attemptNumber, JobState newState) {
}
