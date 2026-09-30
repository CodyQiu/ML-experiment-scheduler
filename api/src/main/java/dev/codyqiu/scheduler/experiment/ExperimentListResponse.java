package dev.codyqiu.scheduler.experiment;

import java.util.List;

/** The newest experiments first, each with its progress. */
public record ExperimentListResponse(List<ExperimentResponse> experiments) {
}
