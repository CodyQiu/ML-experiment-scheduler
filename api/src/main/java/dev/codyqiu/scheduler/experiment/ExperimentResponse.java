package dev.codyqiu.scheduler.experiment;

import java.time.Instant;

import dev.codyqiu.scheduler.task.Task;

public record ExperimentResponse(
		long id,
		String name,
		Task task,
		int maxAttempts,
		Instant createdAt,
		Progress progress) {

	/** Job counts by state. They come from one SQL statement, so they always sum to {@code total}. */
	public record Progress(int total, int queued, int running, int succeeded, int failed) {
	}

}
