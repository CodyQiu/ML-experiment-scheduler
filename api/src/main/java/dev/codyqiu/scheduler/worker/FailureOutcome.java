package dev.codyqiu.scheduler.worker;

import dev.codyqiu.scheduler.job.JobState;

public sealed interface FailureOutcome {

	/** The failure ended the attempt; the job is QUEUED for another attempt or FAILED for good. */
	record Recorded(JobState jobState) implements FailureOutcome {
	}

	/** The guarded update matched no row, so nothing changed. */
	record Rejected(Rejection rejection) implements FailureOutcome {
	}

}
