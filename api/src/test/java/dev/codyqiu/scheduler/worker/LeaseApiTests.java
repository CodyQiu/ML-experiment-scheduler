package dev.codyqiu.scheduler.worker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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

/** Heartbeats and strict leases through the HTTP API. */
class LeaseApiTests extends IntegrationTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RecoveryService recovery;

	@Test
	void aHeartbeatExtendsTheLeaseOfTheRunningAttempt() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		jdbc.sql("UPDATE jobs SET lease_expires_at = now() + interval '1 second' WHERE id = :id").param("id", jobId).update();

		JsonNode renewed = body(heartbeat(jobId, attemptId), HttpStatus.OK);

		assertThat(renewed.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(Instant.parse(renewed.get("leaseExpiresAt").asString())).isEqualTo(storedLease(jobId));
		assertThat(leaseSecondsRemaining(jobId)).isBetween(25.0, 30.0);
		assertThat(jdbc.sql("SELECT last_heartbeat_at IS NOT NULL FROM attempts WHERE id = :id")
			.param("id", UUID.fromString(attemptId))
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void anExpiredLeaseCannotBeRenewedEvenBeforeRecoveryRuns() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		expireLease(jobId);
		Instant expired = storedLease(jobId);

		JsonNode problem = problem(heartbeat(jobId, attemptId), "LEASE_EXPIRED");

		assertThat(problem.get("jobState").asString()).isEqualTo("RUNNING");
		assertThat(storedLease(jobId)).isEqualTo(expired);
	}

	@Test
	void anExpiredLeaseCannotCompleteEvenBeforeRecoveryRuns() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		expireLease(jobId);

		problem(complete(jobId, attemptId, 0.9), "LEASE_EXPIRED");

		JsonNode job = body(mvc.get().uri("/jobs/" + jobId).exchange(), HttpStatus.OK);
		assertThat(job.get("state").asString()).isEqualTo("RUNNING");
		assertThat(job.get("result").isNull()).isTrue();
		// The job is not stuck: the next sweep recovers it.
		assertThat(recovery.recoverExpiredLeases(10)).hasSize(1);
	}

	@Test
	void aStaleAttemptCannotRenewTheLeaseOfTheAttemptThatReplacedIt() throws Exception {
		long jobId = submitJobs(1).get(0);
		String stale = claim("worker-a");
		expireLease(jobId);
		recovery.recoverExpiredLeases(10);
		String current = claim("worker-b");
		Instant currentLease = storedLease(jobId);

		JsonNode problem = problem(heartbeat(jobId, stale), "ATTEMPT_NOT_CURRENT");

		assertThat(problem.get("jobState").asString()).isEqualTo("RUNNING");
		assertThat(storedLease(jobId)).isEqualTo(currentLease);
		assertThat(heartbeat(jobId, current)).hasStatus(HttpStatus.OK);
	}

	@Test
	void anAttemptThatNeverOwnedTheJobCannotRenewIt() throws Exception {
		long jobId = submitJobs(1).get(0);
		claim("worker-1");
		Instant lease = storedLease(jobId);

		problem(heartbeat(jobId, UUID.randomUUID().toString()), "ATTEMPT_NOT_CURRENT");

		assertThat(storedLease(jobId)).isEqualTo(lease);
	}

	@Test
	void aHeartbeatAfterTheResultWasAcceptedIsRejected() throws Exception {
		long jobId = submitJobs(1).get(0);
		String attemptId = claim("worker-1");
		assertThat(complete(jobId, attemptId, 0.9)).hasStatus(HttpStatus.OK);

		JsonNode problem = problem(heartbeat(jobId, attemptId), "ATTEMPT_NOT_CURRENT");

		assertThat(problem.get("jobState").asString()).isEqualTo("SUCCEEDED");
	}

	@Test
	void heartbeatsForUnknownJobsOrWithoutAnAttemptIdAreRejected() throws Exception {
		MvcTestResult unknown = heartbeat(999, UUID.randomUUID().toString());
		assertThat(unknown).hasStatus(HttpStatus.NOT_FOUND);

		MvcTestResult missing = mvc.post().uri("/worker/jobs/1/heartbeat").contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
		assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(json.readTree(missing.getResponse().getContentAsString()).get("errors").get(0).get("field").asString())
			.isEqualTo("attemptId");
	}

	private String claim(String workerId) throws Exception {
		MvcTestResult result = mvc.post()
			.uri("/worker/jobs/claim")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("workerId", workerId)))
			.exchange();
		return body(result, HttpStatus.OK).get("attemptId").asString();
	}

	private MvcTestResult heartbeat(long jobId, String attemptId) {
		return mvc.post()
			.uri("/worker/jobs/" + jobId + "/heartbeat")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("attemptId", attemptId)))
			.exchange();
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

	private JsonNode problem(MvcTestResult result, String code) throws Exception {
		JsonNode problem = body(result, HttpStatus.CONFLICT);
		assertThat(problem.get("code").asString()).isEqualTo(code);
		return problem;
	}

}
