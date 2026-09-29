package dev.codyqiu.scheduler.job;

import java.util.UUID;

/** A RUNNING job whose lease has passed, locked by the recovering transaction. */
public record ExpiredLease(long jobId, UUID attemptId, int attemptNumber, int maxAttempts, String workerId) {

	/** Whether recovery returns the job to the queue (true) or fails it for good (false). */
	public boolean hasAttemptsLeft() {
		return attemptNumber < maxAttempts;
	}

}
