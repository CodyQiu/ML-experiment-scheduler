package dev.codyqiu.scheduler.job;

/** Why the job's most recent unsuccessful attempt ended; shown so failures are visible in lists. */
public record JobError(int attemptNumber, String type, String message) {
}
