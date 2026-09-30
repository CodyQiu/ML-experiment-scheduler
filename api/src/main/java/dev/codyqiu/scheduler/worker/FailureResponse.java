package dev.codyqiu.scheduler.worker;

import dev.codyqiu.scheduler.job.JobState;

/** {@code state} is QUEUED when another attempt will run, FAILED when the job has ended. */
public record FailureResponse(long jobId, JobState state) {
}
