package dev.codyqiu.scheduler.worker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.lease.RecoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Failure reports, attempt history, and lastError through the HTTP API. */
class FailureApiTests extends IntegrationTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RecoveryService recovery;

	@Test
	void aRetryableFailureRequeuesTheJobAndShowsWhy() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");

		JsonNode response = body(fail(jobId, attemptId, true, "WORKER_ERROR", "RuntimeError: boom"), HttpStatus.OK);

		assertThat(response.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(response.get("state").asString()).isEqualTo("QUEUED");
		JsonNode job = body(mvc.get().uri("/jobs/" + jobId).exchange(), HttpStatus.OK);
		assertThat(job.get("state").asString()).isEqualTo("QUEUED");
		assertThat(job.get("lastError")).isEqualTo(json.readTree("""
				{"attemptNumber": 1, "type": "WORKER_ERROR", "message": "RuntimeError: boom"}"""));
		JsonNode listed = body(mvc.get().uri("/experiments/1/jobs").exchange(), HttpStatus.OK).get("jobs").get(0);
		assertThat(listed.get("lastError")).isEqualTo(job.get("lastError"));
	}

	@Test
	void aNonRetryableFailureEndsTheJob() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");

		JsonNode response = body(fail(jobId, attemptId, false, "TRAINING_DIVERGED", "validation loss is nan"),
				HttpStatus.OK);

		assertThat(response.get("state").asString()).isEqualTo("FAILED");
		assertThat(body(mvc.get().uri("/experiments/1").exchange(), HttpStatus.OK).get("progress").get("failed").asInt())
			.isOne();
	}

	@Test
	void theAttemptHistoryExplainsEveryExecution() throws Exception {
		long jobId = submitJobs(1).get(0);
		claim("worker-a");
		expireLease(jobId);
		recovery.recoverExpiredLeases(10);
		String second = claim("worker-b");
		assertThat(fail(jobId, second, true, "WORKER_SHUTDOWN", "aborted by a second stop signal")).hasStatus(HttpStatus.OK);
		String third = claim("worker-c");
		assertThat(mvc.post()
			.uri("/worker/jobs/" + jobId + "/complete")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("attemptId", third, "metrics", TestData.metrics(0.9))))
			.exchange()).hasStatus(HttpStatus.OK);

		JsonNode history = body(mvc.get().uri("/jobs/" + jobId + "/attempts").exchange(), HttpStatus.OK);

		assertThat(history.get("jobId").asLong()).isEqualTo(jobId);
		JsonNode attempts = history.get("attempts");
		assertThat(attempts.size()).isEqualTo(3);
		assertThat(summary(attempts.get(0))).isEqualTo("1 worker-a EXPIRED LEASE_EXPIRED null");
		assertThat(summary(attempts.get(1))).isEqualTo("2 worker-b FAILED WORKER_SHUTDOWN true");
		assertThat(summary(attempts.get(2))).isEqualTo("3 worker-c SUCCEEDED null null");
		// The attempt id is a credential for a running attempt, so history never exposes it.
		attempts.forEach(attempt -> assertThat(attempt.has("attemptId")).isFalse());
		assertThat(attempts.get(1).get("errorMessage").asString()).isEqualTo("aborted by a second stop signal");
		// The job keeps showing its most recent failure even after it succeeded.
		assertThat(body(mvc.get().uri("/jobs/" + jobId).exchange(), HttpStatus.OK).get("lastError").get("type").asString())
			.isEqualTo("WORKER_SHUTDOWN");
	}

	@Test
	void aJobThatNeverFailedHasNoLastError() throws Exception {
		long jobId = submitJobs(1).get(0);

		assertThat(body(mvc.get().uri("/jobs/" + jobId).exchange(), HttpStatus.OK).get("lastError").isNull()).isTrue();
		assertThat(body(mvc.get().uri("/jobs/" + jobId + "/attempts").exchange(), HttpStatus.OK).get("attempts").size())
			.isZero();
	}

	@Test
	void failureReportsFromAttemptsWithoutAuthorityAreRejected() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");

		JsonNode stranger = body(fail(jobId, UUID.randomUUID().toString(), false, "TRAINING_DIVERGED", "x"),
				HttpStatus.CONFLICT);
		assertThat(stranger.get("code").asString()).isEqualTo("ATTEMPT_NOT_CURRENT");

		expireLease(jobId);
		JsonNode late = body(fail(jobId, attemptId, false, "TRAINING_DIVERGED", "x"), HttpStatus.CONFLICT);
		assertThat(late.get("code").asString()).isEqualTo("LEASE_EXPIRED");
	}

	@Test
	void unknownJobsAreNotFound() throws Exception {
		assertThat(fail(999, UUID.randomUUID().toString(), true, "WORKER_ERROR", "x")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/jobs/999/attempts").exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource
	void invalidFailureReportsAreRejected(String description, Map<String, Object> body, String expectedError)
			throws Exception {
		MvcTestResult result = mvc.post()
			.uri("/worker/jobs/1/fail")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(body))
			.exchange();

		JsonNode problem = body(result, HttpStatus.BAD_REQUEST);
		JsonNode error = problem.get("errors").get(0);
		assertThat(error.get("field").asString() + ": " + error.get("message").asString()).isEqualTo(expectedError);
	}

	static Stream<Arguments> invalidFailureReportsAreRejected() {
		return Stream.of(
				Arguments.of("missing retryable", report(Map.of("retryable", "REMOVE")), "retryable: must not be null"),
				Arguments.of("lower-case error type", report(Map.of("errorType", "worker_error")),
						"errorType: must be an UPPER_SNAKE_CASE code of at most 64 characters"),
				Arguments.of("message too long", report(Map.of("message", "x".repeat(2001))),
						"message: size must be between 0 and 2000"));
	}

	private static Map<String, Object> report(Map<String, Object> overrides) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("attemptId", UUID.randomUUID().toString());
		body.put("retryable", true);
		body.put("errorType", "WORKER_ERROR");
		body.put("message", "boom");
		overrides.forEach((key, value) -> {
			if ("REMOVE".equals(value)) {
				body.remove(key);
			}
			else {
				body.put(key, value);
			}
		});
		return body;
	}

	private static String summary(JsonNode attempt) {
		return attempt.get("attemptNumber").asInt() + " " + attempt.get("workerId").asString() + " "
				+ attempt.get("status").asString() + " " + text(attempt.get("errorType")) + " "
				+ text(attempt.get("retryable"));
	}

	private static String text(JsonNode node) {
		return node.isNull() ? "null" : node.asString();
	}

	private String claim(String workerId) throws Exception {
		MvcTestResult result = mvc.post()
			.uri("/worker/jobs/claim")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("workerId", workerId)))
			.exchange();
		return body(result, HttpStatus.OK).get("attemptId").asString();
	}

	private MvcTestResult fail(long jobId, String attemptId, boolean retryable, String errorType, String message) {
		return mvc.post()
			.uri("/worker/jobs/" + jobId + "/fail")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("attemptId", attemptId, "retryable", retryable, "errorType",
					errorType, "message", message)))
			.exchange();
	}

	private JsonNode body(MvcTestResult result, HttpStatus expectedStatus) throws Exception {
		assertThat(result).hasStatus(expectedStatus);
		return json.readTree(result.getResponse().getContentAsString());
	}

}
