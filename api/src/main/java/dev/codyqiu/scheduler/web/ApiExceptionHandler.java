package dev.codyqiu.scheduler.web;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 problem details ({@code application/problem+json}) carrying a
 * machine-readable {@code code}. Field-level problems also list each one under {@code errors}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** One invalid input. {@code field} is its path in the request, e.g. {@code jobs[2].config.epochs}. */
	public record FieldViolation(String field, String message) {
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail handleConflict(ConflictException ex) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage());
		ex.properties().forEach(problem::setProperty);
		return problem;
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error");
	}

	/** Bean Validation failures: every violated constraint is reported, not just the first. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> violations = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
			.toList();
		return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return handleExceptionInternal(ex, describeUnreadableBody(ex), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		// Spring MVC's built-in errors (unknown route, 405, 415, ...) get a code derived from the status.
		if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
			HttpStatus known = HttpStatus.resolve(statusCode.value());
			problem.setProperty("code", (known != null) ? known.name() : "HTTP_" + statusCode.value());
		}
		return response;
	}

	/**
	 * Jackson rejects a body before Bean Validation runs: syntax errors, unknown fields, and wrong
	 * JSON types. When Jackson knows where the problem is, it is reported like a validation error;
	 * only the first such problem is found because parsing stops there.
	 */
	private static ProblemDetail describeUnreadableBody(HttpMessageNotReadableException ex) {
		JacksonException cause = findCause(ex, JacksonException.class);
		if (cause == null) {
			return problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is missing or unreadable");
		}
		if (cause instanceof StreamReadException && !(cause instanceof InputCoercionException)) {
			return problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
					"Malformed JSON: " + cause.getOriginalMessage() + location(cause));
		}
		String field = jsonPath(cause.getPath());
		if (field.isEmpty()) {
			return problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body must be a JSON object");
		}
		return validationProblem(List.of(new FieldViolation(field, describeMappingError(cause))));
	}

	private static String describeMappingError(JacksonException cause) {
		if (cause instanceof UnrecognizedPropertyException) {
			return "unknown field";
		}
		if (cause instanceof InputCoercionException) {
			return "number is out of range";
		}
		Class<?> target = (cause instanceof MismatchedInputException mismatch) ? mismatch.getTargetType() : null;
		if (target == null) {
			return "invalid value";
		}
		if (target.isEnum()) {
			return "must be one of " + Arrays.toString(target.getEnumConstants());
		}
		if (target == Integer.class || target == int.class || target == Long.class || target == long.class) {
			return "must be an integer";
		}
		if (target == Double.class || target == double.class) {
			return "must be a number";
		}
		if (target == String.class) {
			return "must be a string";
		}
		if (target == UUID.class) {
			return "must be a UUID";
		}
		if (Collection.class.isAssignableFrom(target)) {
			return "must be an array";
		}
		return "must be an object";
	}

	/** Renders Jackson's path in the same notation Bean Validation uses, e.g. {@code jobs[0].config.epochs}. */
	private static String jsonPath(List<JacksonException.Reference> path) {
		StringBuilder out = new StringBuilder();
		for (JacksonException.Reference reference : path) {
			if (reference.getPropertyName() != null) {
				out.append(out.isEmpty() ? "" : ".").append(reference.getPropertyName());
			}
			else if (reference.getIndex() >= 0) {
				out.append('[').append(reference.getIndex()).append(']');
			}
		}
		return out.toString();
	}

	private static String location(JacksonException cause) {
		TokenStreamLocation location = cause.getLocation();
		if (location == null || location.getLineNr() < 1) {
			return "";
		}
		return " (line %d, column %d)".formatted(location.getLineNr(), location.getColumnNr());
	}

	private static ProblemDetail validationProblem(List<FieldViolation> violations) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
				"Request has %d invalid field(s)".formatted(violations.size()));
		problem.setProperty("errors", violations);
		return problem;
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

	private static boolean hasCode(ProblemDetail problem) {
		return problem.getProperties() != null && problem.getProperties().containsKey("code");
	}

	private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
		for (Throwable current = ex; current != null; current = current.getCause()) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
		}
		return null;
	}

}
