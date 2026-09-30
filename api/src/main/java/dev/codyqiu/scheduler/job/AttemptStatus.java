package dev.codyqiu.scheduler.job;

/** How an attempt ended. Only {@code RUNNING} attempts can still change. */
public enum AttemptStatus {

	RUNNING,
	SUCCEEDED,
	/** The worker reported a failure. */
	FAILED,
	/** The lease ran out before the attempt reported anything. */
	EXPIRED

}
