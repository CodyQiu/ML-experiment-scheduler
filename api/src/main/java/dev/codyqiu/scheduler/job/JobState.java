package dev.codyqiu.scheduler.job;

/**
 * Job lifecycle. {@code SUCCEEDED} and {@code FAILED} are terminal. A job returns from
 * {@code RUNNING} to {@code QUEUED} only through a retry, which arrives with leases.
 */
public enum JobState {

	QUEUED,
	RUNNING,
	SUCCEEDED,
	FAILED

}
