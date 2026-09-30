package dev.codyqiu.scheduler.experiment;

import java.util.List;

import dev.codyqiu.scheduler.job.RankedJob;

/** The top successful jobs of an experiment. {@code metric} names the ranking metric. */
public record BestJobsResponse(long experimentId, String metric, List<RankedJob> jobs) {
}
