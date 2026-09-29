package dev.codyqiu.scheduler.job;

import java.util.stream.Stream;

import dev.codyqiu.scheduler.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CHECK constraints and unique indexes are the last line of defense: whatever code path
 * writes a row, the database refuses states the lifecycle does not allow. Each case violates
 * exactly one constraint.
 */
class JobSchemaInvariantTests extends IntegrationTest {

	private static final String CLAIMED = """
			attempt_count = 1, current_attempt_id = gen_random_uuid(), worker_id = 'worker-a', started_at = now()""";

	private static final String LEASED = "lease_expires_at = now() + interval '30 seconds'";

	@BeforeEach
	void insertOneQueuedJob() {
		jdbc.sql("INSERT INTO experiments (name, task, max_attempts) VALUES ('invariants', 'synthetic-mlp-v1', 2)")
			.update();
		jdbc.sql("""
				INSERT INTO jobs (experiment_id, job_index, seed, config, max_attempts)
				VALUES (1, 0, 0, '{"epochs": 1}', 2)
				""").update();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource
	void databaseRejectsInconsistentJobs(String violation, String constraint, String assignments) {
		assertThatThrownBy(() -> jdbc.sql("UPDATE jobs SET " + assignments).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

	static Stream<Arguments> databaseRejectsInconsistentJobs() {
		return Stream.of(
				Arguments.of("running without an attempt identity", "jobs_claim_fields_match_state",
						"state = 'RUNNING', " + LEASED),
				Arguments.of("queued with no attempts left", "jobs_queued_has_attempts_left",
						"attempt_count = 2"),
				Arguments.of("more attempts than the budget", "jobs_attempt_count_within_budget",
						"state = 'RUNNING', " + LEASED + ", " + CLAIMED.replace("attempt_count = 1", "attempt_count = 3")),
				Arguments.of("running without a lease", "jobs_lease_iff_running",
						"state = 'RUNNING', " + CLAIMED),
				Arguments.of("queued with a lease", "jobs_lease_iff_running",
						LEASED),
				Arguments.of("succeeded without a result", "jobs_result_iff_succeeded",
						"state = 'SUCCEEDED', val_accuracy = 0.5, finished_at = now(), " + CLAIMED),
				Arguments.of("finished while still running", "jobs_finished_iff_terminal",
						"state = 'RUNNING', finished_at = now(), " + LEASED + ", " + CLAIMED),
				Arguments.of("accuracy above 1", "jobs_val_accuracy_range",
						"state = 'SUCCEEDED', val_accuracy = 1.5, result = '{}', finished_at = now(), " + CLAIMED));
	}

	@Test
	void databaseAcceptsTheLegalLifecycle() {
		assertThat(jdbc.sql("UPDATE jobs SET state = 'RUNNING', " + LEASED + ", " + CLAIMED).update()).isOne();
		assertThat(jdbc.sql("""
				UPDATE jobs
				SET state = 'SUCCEEDED', val_accuracy = 0.75, result = '{"valAccuracy": 0.75}', finished_at = now(),
				    lease_expires_at = NULL
				""").update()).isOne();
	}

	@ParameterizedTest(name = "a second {0} attempt")
	@MethodSource
	void aJobHasAtMostOneRunningAndOneSucceededAttempt(String status, String index) {
		String insert = """
				INSERT INTO attempts (id, job_id, attempt_number, worker_id, status, claimed_at, finished_at)
				VALUES (gen_random_uuid(), 1, :number, 'worker-a', :status, now(),
				        CASE WHEN :status = 'RUNNING' THEN NULL ELSE now() END)
				""";
		jdbc.sql(insert).param("number", 1).param("status", status).update();

		assertThatThrownBy(() -> jdbc.sql(insert).param("number", 2).param("status", status).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(index);
	}

	static Stream<Arguments> aJobHasAtMostOneRunningAndOneSucceededAttempt() {
		return Stream.of(Arguments.of("RUNNING", "attempts_one_running_per_job"),
				Arguments.of("SUCCEEDED", "attempts_one_success_per_job"));
	}

}
