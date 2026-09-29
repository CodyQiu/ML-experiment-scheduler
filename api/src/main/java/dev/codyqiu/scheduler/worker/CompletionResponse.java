package dev.codyqiu.scheduler.worker;

import dev.codyqiu.scheduler.job.JobState;

public record CompletionResponse(long jobId, JobState state) {
}
