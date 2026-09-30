package dev.codyqiu.scheduler.worker;

import dev.codyqiu.scheduler.job.AttemptStanding;
import dev.codyqiu.scheduler.job.JobState;

/** Why a request made on behalf of an attempt was refused. The request changed nothing. */
public record Rejection(Reason reason, JobState jobState) {

	public enum Reason {

		/** The attempt still holds the running job, but its lease has passed (strict leases). */
		LEASE_EXPIRED,

		/** The job is not running under this attempt: reassigned, finished, or never this attempt's. */
		ATTEMPT_NOT_CURRENT,

		/** This attempt's result was already accepted, and the new report carries a different one. */
		RESULT_CONFLICT

	}

	static Rejection explain(AttemptStanding standing) {
		Reason reason = standing.isExpiredLease() ? Reason.LEASE_EXPIRED : Reason.ATTEMPT_NOT_CURRENT;
		return new Rejection(reason, standing.jobState());
	}

}
