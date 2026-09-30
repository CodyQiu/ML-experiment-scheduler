package dev.codyqiu.scheduler.job;

/** The result of an accepted failure report: the job's new state and how much budget was used. */
public record FailedAttempt(JobState newState, int attemptNumber, int maxAttempts) {
}
