package dev.codyqiu.scheduler.worker;

import java.util.Map;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.lease.RecoveryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeated completion reports. Execution may happen more than once, but the service accepts at
 * most one result per job. An identical repeat of the accepted report is acknowledged; nothing
 * else ever replaces it.
 */
class CompletionReplayTests extends IntegrationTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RecoveryService recovery;

	@Test
	void anIdenticalRetryAfterALostAcknowledgementIsReplayedAndChangesNothing() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		JsonNode first = body(complete(jobId, attemptId, 0.9), HttpStatus.OK);
		String stored = storedOutcome(jobId);

		// The worker never saw that response, so it sends the same report again.
		JsonNode retry = body(complete(jobId, attemptId, 0.9), HttpStatus.OK);

		assertThat(first.get("replayed").asBoolean()).isFalse();
		assertThat(retry.get("replayed").asBoolean()).isTrue();
		assertThat(retry.get("state").asString()).isEqualTo("SUCCEEDED");
		assertThat(storedOutcome(jobId)).isEqualTo(stored); // same result, same finished_at
	}

	@Test
	void theAcceptedAttemptReportingADifferentResultIsAConflict() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		complete(jobId, attemptId, 0.9);
		String stored = storedOutcome(jobId);

		JsonNode problem = body(complete(jobId, attemptId, 0.5), HttpStatus.CONFLICT);

		assertThat(problem.get("code").asString()).isEqualTo("RESULT_CONFLICT");
		assertThat(storedOutcome(jobId)).isEqualTo(stored);
	}

	@Test
	void aStaleAttemptIsNeverTreatedAsAReplayEvenWithAnIdenticalPayload() throws Exception {
		long jobId = submitJobs(1).get(0);
		String stale = claim("worker-a");
		expireLease(jobId);
		recovery.recoverExpiredLeases(10);
		String current = claim("worker-b");
		complete(jobId, current, 0.9);

		JsonNode problem = body(complete(jobId, stale, 0.9), HttpStatus.CONFLICT);

		assertThat(problem.get("code").asString()).isEqualTo("ATTEMPT_NOT_CURRENT");
	}

	@Test
	void aFirstReportArrivingAfterTheLeaseExpiredIsNotAReplay() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		expireLease(jobId);

		JsonNode problem = body(complete(jobId, attemptId, 0.9), HttpStatus.CONFLICT);

		assertThat(problem.get("code").asString()).isEqualTo("LEASE_EXPIRED");
		assertThat(storedOutcome(jobId)).isEqualTo("RUNNING null");
	}

	private String storedOutcome(long jobId) {
		return jdbc.sql("SELECT state || ' ' || COALESCE(result::text || ' at ' || finished_at::text, 'null') FROM jobs WHERE id = :id")
			.param("id", jobId)
			.query(String.class)
			.single();
	}

	private String claim(String workerId) throws Exception {
		MvcTestResult result = mvc.post()
			.uri("/worker/jobs/claim")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("workerId", workerId)))
			.exchange();
		return body(result, HttpStatus.OK).get("attemptId").asString();
	}

	private MvcTestResult complete(long jobId, String attemptId, double valAccuracy) {
		return mvc.post()
			.uri("/worker/jobs/" + jobId + "/complete")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("attemptId", attemptId, "metrics", TestData.metrics(valAccuracy))))
			.exchange();
	}

	private JsonNode body(MvcTestResult result, HttpStatus expectedStatus) throws Exception {
		assertThat(result).hasStatus(expectedStatus);
		return json.readTree(result.getResponse().getContentAsString());
	}

}
