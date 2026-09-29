package dev.codyqiu.scheduler;

import dev.codyqiu.scheduler.task.Optimizer;
import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.TrainingMetrics;

public final class TestData {

	public static final SyntheticMlpConfig CONFIG = new SyntheticMlpConfig(0.01, 32, 2, 64, 20, Optimizer.ADAM, 0.0);

	private TestData() {
	}

	/** Metrics that differ only in {@code valAccuracy}, so a test can tell which report was stored. */
	public static TrainingMetrics metrics(double valAccuracy) {
		return new TrainingMetrics(valAccuracy, 0.25, 0.2, 1.5);
	}

}
