package dev.codyqiu.scheduler.task;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Optimizer {

	SGD("sgd"),
	ADAM("adam");

	private final String id;

	Optimizer(String id) {
		this.id = id;
	}

	@JsonValue
	public String id() {
		return id;
	}

	@Override
	public String toString() {
		return id;
	}

}
