package dev.codyqiu.scheduler.worker;

import dev.codyqiu.scheduler.job.JobState;

/** {@code replayed} is true when this request repeated a report that had already been accepted. */
public record CompletionResponse(long jobId, JobState state, boolean replayed) {
}
