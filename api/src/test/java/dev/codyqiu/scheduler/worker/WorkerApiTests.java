package dev.codyqiu.scheduler.worker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.lease.RecoveryService;
import dev.codyqiu.scheduler.task.TrainingMetrics;
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

class WorkerApiTests extends IntegrationTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RecoveryService recovery;

	@Test
	void claimHandsOutTheOldestQueuedJobUnderAFreshAttempt() throws Exception {
		List<Long> jobIds = submitJobs(2);

		JsonNode first = body(claim("worker-1"), HttpStatus.OK);
		assertThat(first.get("jobId").asLong()).isEqualTo(jobIds.get(0));
		assertThat(first.get("attemptNumber").asInt()).isEqualTo(1);
		assertThat(UUID.fromString(first.get("attemptId").asString())).isNotNull();
		assertThat(first.get("experimentId").asLong()).isEqualTo(1);
		assertThat(first.get("task").asString()).isEqualTo("synthetic-mlp-v1");
		assertThat(first.get("seed").asInt()).isZero();
		assertThat(first.get("config")).isEqualTo(json.valueToTree(TestData.CONFIG));
		assertThat(first.get("leaseSeconds").asDouble()).isEqualTo(30.0);
		assertThat(first.get("heartbeatIntervalSeconds").asDouble()).isEqualTo(10.0);
		assertThat(Instant.parse(first.get("leaseExpiresAt").asString())).isEqualTo(storedLease(jobIds.get(0)));
		assertThat(leaseSecondsRemaining(jobIds.get(0))).isBetween(25.0, 30.0);
		assertThat(attemptStatus(UUID.fromString(first.get("attemptId").asString()))).isEqualTo("RUNNING");

		JsonNode job = body(get("/jobs/" + jobIds.get(0)), HttpStatus.OK);
		assertThat(job.get("state").asString()).isEqualTo("RUNNING");
		assertThat(job.get("attemptCount").asInt()).isEqualTo(1);
		assertThat(job.get("workerId").asString()).isEqualTo("worker-1");
		assertThat(job.get("startedAt").isNull()).isFalse();

		JsonNode second = body(claim("worker-2"), HttpStatus.OK);
		assertThat(second.get("jobId").asLong()).isEqualTo(jobIds.get(1));
		assertThat(second.get("attemptId")).isNotEqualTo(first.get("attemptId"));
	}

	@Test
	void claimsFollowSubmissionOrderAcrossExperiments() throws Exception {
		List<Long> older = submitJobs(2);
		List<Long> newer = submitJobs(1);

		List<Long> claimed = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			claimed.add(body(claim("worker-1"), HttpStatus.OK).get("jobId").asLong());
		}
		assertThat(claimed).containsExactly(older.get(0), older.get(1), newer.get(0));
		assertThat(claim("worker-1")).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void emptyQueueReturnsNoContent() throws Exception {
		MvcTestResult result = claim("worker-1");

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(result.getResponse().getContentAsString()).isEmpty();
	}

	@ParameterizedTest
	@MethodSource
	void invalidWorkerIdsAreRejected(String workerId) throws Exception {
		JsonNode problem = problem(claim(workerId), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
		assertThat(fieldErrors(problem))
			.containsExactly("workerId: must be 1-64 characters: letters, digits, '.', '_' or '-'");
	}

	static Stream<String> invalidWorkerIdsAreRejected() {
		return Stream.of("", "has space", "a".repeat(65), "semi;colon");
	}

	@Test
	void claimWithoutWorkerIdIsRejected() throws Exception {
		MvcTestResult result = mvc.post().uri("/worker/jobs/claim").contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

		assertThat(fieldErrors(problem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED")))
			.containsExactly("workerId: must not be null");
	}

	@Test
	void completionRecordsTheResultAndFinishesTheJob() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = body(claim("worker-1"), HttpStatus.OK).get("attemptId").asString();

		JsonNode completion = body(complete(jobId, attemptId, TestData.metrics(0.93)), HttpStatus.OK);
		assertThat(completion.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(completion.get("state").asString()).isEqualTo("SUCCEEDED");

		JsonNode job = body(get("/jobs/" + jobId), HttpStatus.OK);
		assertThat(job.get("state").asString()).isEqualTo("SUCCEEDED");
		assertThat(job.get("valAccuracy").asDouble()).isEqualTo(0.93);
		assertThat(job.get("result")).isEqualTo(json.valueToTree(TestData.metrics(0.93)));
		assertThat(job.get("finishedAt").isNull()).isFalse();
		JsonNode progress = body(get("/experiments/1"), HttpStatus.OK).get("progress");
		assertThat(progress.get("succeeded").asInt()).isEqualTo(1);
		assertThat(progress.get("running").asInt()).isZero();
		assertThat(claim("worker-1")).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void completionByAnAttemptThatNeverOwnedTheJobIsRejected() throws Exception {
		long jobId = submitJobs(1).get(0);
		body(claim("worker-1"), HttpStatus.OK);

		JsonNode problem = problem(complete(jobId, UUID.randomUUID().toString(), TestData.metrics(0.9)),
				HttpStatus.CONFLICT, "ATTEMPT_NOT_CURRENT");
		assertThat(problem.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(problem.get("jobState").asString()).isEqualTo("RUNNING");

		JsonNode job = body(get("/jobs/" + jobId), HttpStatus.OK);
		assertThat(job.get("state").asString()).isEqualTo("RUNNING");
		assertThat(job.get("result").isNull()).isTrue();
	}

	@Test
	void aSupersededAttemptCannotCompleteTheReassignedJob() throws Exception {
		long jobId = submitJobs(1).get(0);
		String staleAttempt = body(claim("worker-a"), HttpStatus.OK).get("attemptId").asString();
		// worker-a stops heartbeating; recovery re-queues the job and worker-b claims it.
		expireLease(jobId);
		assertThat(recovery.recoverExpiredLeases(10)).hasSize(1);
		JsonNode reassignment = body(claim("worker-b"), HttpStatus.OK);
		assertThat(reassignment.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(reassignment.get("attemptNumber").asInt()).isEqualTo(2);
		String currentAttempt = reassignment.get("attemptId").asString();

		JsonNode staleWhileRunning = problem(complete(jobId, staleAttempt, TestData.metrics(0.10)), HttpStatus.CONFLICT,
				"ATTEMPT_NOT_CURRENT");
		assertThat(staleWhileRunning.get("jobState").asString()).isEqualTo("RUNNING");
		assertThat(complete(jobId, currentAttempt, TestData.metrics(0.80))).hasStatus(HttpStatus.OK);
		JsonNode staleAfterSuccess = problem(complete(jobId, staleAttempt, TestData.metrics(0.10)), HttpStatus.CONFLICT,
				"ATTEMPT_NOT_CURRENT");
		assertThat(staleAfterSuccess.get("jobState").asString()).isEqualTo("SUCCEEDED");

		JsonNode job = body(get("/jobs/" + jobId), HttpStatus.OK);
		assertThat(job.get("workerId").asString()).isEqualTo("worker-b");
		assertThat(job.get("valAccuracy").asDouble()).isEqualTo(0.80);
	}

	@Test
	void anAcceptedResultIsNeverOverwritten() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = body(claim("worker-1"), HttpStatus.OK).get("attemptId").asString();
		assertThat(complete(jobId, attemptId, TestData.metrics(0.90))).hasStatus(HttpStatus.OK);

		// Even the attempt that produced the result cannot replace it. (Milestone 2 turns an
		// identical retry into a 200 replay; a different payload stays a conflict.)
		JsonNode problem = problem(complete(jobId, attemptId, TestData.metrics(0.10)), HttpStatus.CONFLICT,
				"ATTEMPT_NOT_CURRENT");
		assertThat(problem.get("jobState").asString()).isEqualTo("SUCCEEDED");
		assertThat(body(get("/jobs/" + jobId), HttpStatus.OK).get("result"))
			.isEqualTo(json.valueToTree(TestData.metrics(0.90)));
	}

	@Test
	void completingAnUnknownJobReturnsNotFound() throws Exception {
		problem(complete(999, UUID.randomUUID().toString(), TestData.metrics(0.9)), HttpStatus.NOT_FOUND, "NOT_FOUND");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource
	void invalidCompletionReportsAreRejected(String description, String body, String expectedError) throws Exception {
		// Job 1 does not exist: validation must reject the report before any state is read.
		MvcTestResult result = mvc.post()
			.uri("/worker/jobs/1/complete")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();

		assertThat(fieldErrors(problem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED"))).containsExactly(expectedError);
	}

	static Stream<Arguments> invalidCompletionReportsAreRejected() {
		return Stream.of(
				Arguments.of("accuracy above 1", completionJson("valAccuracy", "1.5"),
						"metrics.valAccuracy: must be less than or equal to 1.0"),
				Arguments.of("negative loss", completionJson("valLoss", "-0.1"),
						"metrics.valLoss: must be greater than or equal to 0.0"),
				Arguments.of("overflow to infinity", completionJson("trainLoss", "1e400"),
						"metrics.trainLoss: must be less than or equal to 1000000"),
				Arguments.of("missing metric", completionJson("trainingSeconds", "null"),
						"metrics.trainingSeconds: must not be null"),
				Arguments.of("string metric", completionJson("valLoss", "\"low\""), "metrics.valLoss: must be a number"),
				Arguments.of("unknown metric", completionJson("f1", "0.5"), "metrics.f1: unknown field"),
				Arguments.of("malformed attempt id",
						completionJson("valAccuracy", "0.9").replaceFirst("\"attemptId\": \"[^\"]+\"", "\"attemptId\": \"nope\""),
						"attemptId: must be a UUID"),
				Arguments.of("missing metrics", "{\"attemptId\": \"" + UUID.randomUUID() + "\"}",
						"metrics: must not be null"));
	}

	/** A completion body built from raw JSON literals, with one metric overridden or added. */
	private static String completionJson(String metric, String rawValue) {
		Map<String, String> metrics = new LinkedHashMap<>();
		metrics.put("valAccuracy", "0.9");
		metrics.put("valLoss", "0.25");
		metrics.put("trainLoss", "0.2");
		metrics.put("trainingSeconds", "1.5");
		metrics.put(metric, rawValue);
		String fields = metrics.entrySet()
			.stream()
			.map(entry -> "\"" + entry.getKey() + "\": " + entry.getValue())
			.collect(Collectors.joining(", "));
		return "{\"attemptId\": \"" + UUID.randomUUID() + "\", \"metrics\": {" + fields + "}}";
	}

	private MvcTestResult claim(String workerId) {
		return mvc.post()
			.uri("/worker/jobs/claim")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("workerId", workerId)))
			.exchange();
	}

	private MvcTestResult complete(long jobId, String attemptId, TrainingMetrics metrics) {
		return mvc.post()
			.uri("/worker/jobs/" + jobId + "/complete")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("attemptId", attemptId, "metrics", metrics)))
			.exchange();
	}

	private MvcTestResult get(String path) {
		return mvc.get().uri(path).exchange();
	}

	private JsonNode body(MvcTestResult result, HttpStatus expectedStatus) throws Exception {
		assertThat(result).hasStatus(expectedStatus);
		return json.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode problem(MvcTestResult result, HttpStatus status, String code) throws Exception {
		assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		JsonNode problem = json.readTree(result.getResponse().getContentAsString());
		assertThat(problem.get("code").asString()).isEqualTo(code);
		return problem;
	}

	private static List<String> fieldErrors(JsonNode problem) {
		List<String> errors = new ArrayList<>();
		for (JsonNode error : problem.get("errors")) {
			errors.add(error.get("field").asString() + ": " + error.get("message").asString());
		}
		return errors;
	}

}
