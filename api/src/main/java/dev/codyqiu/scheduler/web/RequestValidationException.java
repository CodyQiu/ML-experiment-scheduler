package dev.codyqiu.scheduler.web;

/** An invalid part of the request outside the body (e.g. a header), reported like a field error. */
public class RequestValidationException extends RuntimeException {

	private final String field;

	public RequestValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}

}
