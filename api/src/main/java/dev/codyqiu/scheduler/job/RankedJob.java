package dev.codyqiu.scheduler.job;

import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.TrainingMetrics;

/** A successful job's place in its experiment's ranking by {@code valAccuracy} (1 is best). */
public record RankedJob(
		int rank,
		long jobId,
		int jobIndex,
		int seed,
		SyntheticMlpConfig config,
		double valAccuracy,
		TrainingMetrics metrics) {
}
