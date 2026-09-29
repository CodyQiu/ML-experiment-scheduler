package dev.codyqiu.scheduler.task;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The fixed training tasks workers know how to run. A task id pins the dataset, model, and
 * metric semantics, so a changed task gets a new id instead of silently changing old results.
 */
public enum Task {

	SYNTHETIC_MLP_V1("synthetic-mlp-v1");

	private final String id;

	Task(String id) {
		this.id = id;
	}

	/** Stable identifier used in JSON and in the database. */
	@JsonValue
	public String id() {
		return id;
	}

	public static Task fromId(String id) {
		for (Task task : values()) {
			if (task.id.equals(id)) {
				return task;
			}
		}
		throw new IllegalArgumentException("Unknown task id: " + id);
	}

	@Override
	public String toString() {
		return id;
	}

}
