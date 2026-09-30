package dev.codyqiu.scheduler.job;

import java.util.List;

public record JobAttemptsResponse(long jobId, List<AttemptResponse> attempts) {
}
