package dev.codyqiu.scheduler.experiment;

import java.util.List;

import dev.codyqiu.scheduler.job.JobResponse;

/** Wrapped in an object rather than a bare array so paging or filters can be added compatibly. */
public record ExperimentJobsResponse(long experimentId, List<JobResponse> jobs) {
}
