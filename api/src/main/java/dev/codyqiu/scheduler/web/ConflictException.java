package dev.codyqiu.scheduler.web;

import java.util.Map;

/**
 * A well-formed request that conflicts with the current state of a resource (HTTP 409). The
 * {@code properties} are added to the problem details so clients can react without parsing text.
 */
public class ConflictException extends RuntimeException {

	private final String code;

	private final Map<String, Object> properties;

	public ConflictException(String code, String detail, Map<String, Object> properties) {
		super(detail);
		this.code = code;
		this.properties = Map.copyOf(properties);
	}

	public String code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}

}
