package dev.codyqiu.scheduler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.codyqiu.scheduler.experiment.CreateExperimentRequest;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobSpec;
import dev.codyqiu.scheduler.lease.RecoveryService;
import dev.codyqiu.scheduler.lease.RecoverySweeper;
import dev.codyqiu.scheduler.lease.SchedulerProperties;
import dev.codyqiu.scheduler.task.Task;
import dev.codyqiu.scheduler.worker.WorkerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fields of the API's log events, which scripts/demo.sh and log queries rely on. Each event is
 * captured from a real call and rendered by Spring Boot's ECS encoder, the format compose.yaml
 * selects, so the assertions are about the JSON line the stack actually prints.
 */
class StructuredLogTests extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	WorkerService workers;

	@Autowired
	RecoveryService recovery;

	@Autowired
	SchedulerProperties properties;

	@Autowired
	Environment environment;

	private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

	private final StructuredLogEncoder ecs = new StructuredLogEncoder();

	@BeforeEach
	void captureEvents() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		captured.setContext(context);
		captured.start();
		applicationLogger().addAppender(captured);

		// The encoder looks the Spring Environment up in its logger context, as it does at runtime.
		LoggerContext encoderContext = new LoggerContext();
		encoderContext.putObject(Environment.class.getName(), environment);
		ecs.setContext(encoderContext);
		ecs.setFormat("ecs");
		ecs.start();
	}

	@AfterEach
	void releaseEvents() {
		applicationLogger().detachAppender(captured);
		ecs.stop();
	}

	@Test
	void aClaimCarriesEveryIdOfItsAttempt() {
		long jobId = submitJobs(1).get(0);
		JobAssignment claimed = workers.claim("worker-1").orElseThrow();

		JsonNode line = event("job.claimed");

		assertThat(line.get("jobId").isIntegralNumber()).isTrue();
		assertThat(line.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(line.get("experimentId").asLong()).isEqualTo(claimed.experimentId());
		assertThat(line.get("attemptNumber").asInt()).isEqualTo(1);
		assertThat(line.get("attemptId").asString()).isEqualTo(claimed.attemptId().toString());
		assertThat(line.get("workerId").asString()).isEqualTo("worker-1");
		// The ECS envelope: nested keys, the service's name, and the human-readable message.
		assertThat(line.at("/log/level").asString()).isEqualTo("INFO");
		assertThat(line.at("/service/name").asString()).isEqualTo("scheduler-api");
		assertThat(line.get("message").asString())
			.isEqualTo("Claimed job %d attempt 1 (%s) for worker worker-1".formatted(jobId, claimed.attemptId()));
	}

	@Test
	void recoveryAndTheStaleAttemptsReportsAreLoggedWithTheCodesTheCallerSees() {
		long jobId = submitJobs(1).get(0);
		UUID stale = workers.claim("worker-a").orElseThrow().attemptId();
		expireLease(jobId);
		recovery.recoverExpiredLeases(10);
		workers.claim("worker-b");

		workers.heartbeat(jobId, stale);
		workers.complete(jobId, stale, TestData.metrics(0.9));
		workers.fail(jobId, stale, true, "WORKER_ERROR", "boom");

		JsonNode expired = event("lease.expired");
		assertThat(expired.get("jobId").asLong()).isEqualTo(jobId);
		assertThat(expired.get("attemptNumber").asInt()).isEqualTo(1);
		assertThat(expired.get("attemptId").asString()).isEqualTo(stale.toString());
		assertThat(expired.get("workerId").asString()).isEqualTo("worker-a");
		assertThat(expired.get("jobState").asString()).isEqualTo("QUEUED");
		assertThat(expired.get("maxAttempts").asInt()).isEqualTo(3);
		assertThat(expired.at("/log/level").asString()).isEqualTo("WARN");
		for (String action : List.of("heartbeat.rejected", "result.rejected", "failure.rejected")) {
			JsonNode rejected = event(action);
			assertThat(rejected.get("jobId").asLong()).as(action).isEqualTo(jobId);
			assertThat(rejected.get("attemptId").asString()).as(action).isEqualTo(stale.toString());
			assertThat(rejected.get("code").asString()).as(action).isEqualTo("ATTEMPT_NOT_CURRENT");
			assertThat(rejected.get("jobState").asString()).as(action).isEqualTo("RUNNING");
		}
	}

	@Test
	void resultsAreLoggedAsAcceptedReplayedOrConflicting() {
		long jobId = submitJobs(1).get(0);
		UUID attemptId = workers.claim("worker-1").orElseThrow().attemptId();

		workers.complete(jobId, attemptId, TestData.metrics(0.9));
		workers.complete(jobId, attemptId, TestData.metrics(0.9));
		workers.complete(jobId, attemptId, TestData.metrics(0.5));

		JsonNode accepted = event("result.accepted");
		assertThat(accepted.get("valAccuracy").isNumber()).isTrue();
		assertThat(accepted.get("valAccuracy").asDouble()).isEqualTo(0.9);
		assertThat(accepted.get("attemptId").asString()).isEqualTo(attemptId.toString());
		assertThat(event("result.replayed").get("jobId").asLong()).isEqualTo(jobId);
		JsonNode conflict = event("result.rejected");
		assertThat(conflict.get("code").asString()).isEqualTo("RESULT_CONFLICT");
		assertThat(conflict.get("jobState").asString()).isEqualTo("SUCCEEDED");
	}

	@Test
	void aReportedFailureCarriesItsClassificationAndTheJobsNewState() {
		long jobId = submitJobs(1).get(0);
		UUID attemptId = workers.claim("worker-1").orElseThrow().attemptId();

		workers.fail(jobId, attemptId, true, "WORKER_ERROR", "boom");

		JsonNode failure = event("failure.recorded");
		assertThat(failure.get("errorType").asString()).isEqualTo("WORKER_ERROR");
		assertThat(failure.get("retryable").isBoolean()).isTrue();
		assertThat(failure.get("retryable").asBoolean()).isTrue();
		assertThat(failure.get("jobState").asString()).isEqualTo("QUEUED");
		assertThat(failure.get("attemptNumber").asInt()).isEqualTo(1);
		assertThat(failure.get("maxAttempts").asInt()).isEqualTo(3);
	}

	@Test
	void submissionsAreLoggedWithTheirExperimentAndKey() {
		CreateExperimentRequest request = new CreateExperimentRequest("logged", Task.SYNTHETIC_MLP_V1, 2,
				List.of(new JobSpec(0, TestData.CONFIG), new JobSpec(1, TestData.CONFIG)));
		CreateExperimentRequest different = new CreateExperimentRequest("other", Task.SYNTHETIC_MLP_V1, 2,
				List.of(new JobSpec(0, TestData.CONFIG)));

		experiments.submit(request, "key-1");
		experiments.submit(request, "key-1");
		experiments.submit(different, "key-1");

		JsonNode created = event("experiment.created");
		long experimentId = created.get("experimentId").asLong();
		assertThat(created.get("jobCount").asInt()).isEqualTo(2);
		assertThat(created.get("maxAttempts").asInt()).isEqualTo(2);
		assertThat(created.get("idempotencyKey").asString()).isEqualTo("key-1");
		assertThat(event("submission.replayed").get("experimentId").asLong()).isEqualTo(experimentId);
		assertThat(event("submission.key_reused").get("experimentId").asLong()).isEqualTo(experimentId);
	}

	@Test
	void aSubmissionWithoutAKeyHasNoKeyField() {
		submitJobs(1);

		assertThat(event("experiment.created").has("idempotencyKey")).isFalse();
	}

	@Test
	void theRecoveryPolicyIsLoggedAsNumbers() {
		// The periodic sweep is off in tests, so configure a sweeper by hand; nothing is scheduled.
		new RecoverySweeper(recovery, properties).configureTasks(new ScheduledTaskRegistrar());

		JsonNode policy = event("recovery.policy");

		assertThat(policy.get("sweepIntervalSeconds").asDouble()).isEqualTo(5.0);
		assertThat(policy.get("batchSize").asInt()).isEqualTo(100);
		assertThat(policy.get("leaseSeconds").asDouble()).isEqualTo(30.0);
		assertThat(policy.get("heartbeatIntervalSeconds").asDouble()).isEqualTo(10.0);
	}

	/** The single captured event with this {@code event.action}, as one line of ECS JSON. */
	private JsonNode event(String action) {
		List<ILoggingEvent> matching = captured.list.stream().filter((event) -> action.equals(actionOf(event))).toList();
		assertThat(matching).as("events with event.action %s", action).hasSize(1);
		String line = new String(ecs.encode(matching.get(0)), StandardCharsets.UTF_8);
		assertThat(line.strip()).as("one line").doesNotContain("\n");
		JsonNode json = JSON.readTree(line);
		// The dotted key is nested, as ECS expects: {"event": {"action": ...}}.
		assertThat(json.at("/event/action").asString()).isEqualTo(action);
		return json;
	}

	private static Object actionOf(ILoggingEvent event) {
		if (event.getKeyValuePairs() == null) {
			return null;
		}
		return event.getKeyValuePairs()
			.stream()
			.filter((pair) -> pair.key.equals("event.action"))
			.map((pair) -> pair.value)
			.findFirst()
			.orElse(null);
	}

	private static Logger applicationLogger() {
		return (Logger) LoggerFactory.getLogger("dev.codyqiu.scheduler");
	}

}
