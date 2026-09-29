package dev.codyqiu.scheduler.job;

/**
 * Where a job stands relative to one attempt. Read only after a guarded update has already
 * rejected that attempt, to explain why.
 */
public record AttemptStanding(JobState jobState, boolean isCurrentAttempt, boolean leaseExpired) {

	/** The attempt still holds the running job, but its lease has passed: strict leases reject it. */
	public boolean isExpiredLease() {
		return jobState == JobState.RUNNING && isCurrentAttempt && leaseExpired;
	}

}
