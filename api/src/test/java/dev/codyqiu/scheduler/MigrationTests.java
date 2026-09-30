package dev.codyqiu.scheduler;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Migrations run against databases that already hold data, so they are tested that way too. */
class MigrationTests {

	@Test
	void upgradingFromV1MakesStuckJobsRecoverableAndExplainsEveryAttempt() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE))) {
			postgres.start();
			DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword());
			JdbcClient jdbc = JdbcClient.create(dataSource);

			Flyway.configure().dataSource(dataSource).target("1").load().migrate();
			// V1-era rows: queued, RUNNING (without a lease, so V1 could never recover it), succeeded, failed.
			jdbc.sql("INSERT INTO experiments (name, task, max_attempts) VALUES ('legacy', 'synthetic-mlp-v1', 3)")
				.update();
			jdbc.sql("""
					INSERT INTO jobs (experiment_id, job_index, seed, config, max_attempts, state, attempt_count,
					                  current_attempt_id, worker_id, started_at, val_accuracy, result, finished_at)
					VALUES (1, 0, 0, '{}', 3, 'QUEUED', 0, NULL, NULL, NULL, NULL, NULL, NULL),
					       (1, 1, 1, '{}', 3, 'RUNNING', 1, '00000000-0000-0000-0000-000000000001', 'old-worker',
					        now(), NULL, NULL, NULL),
					       (1, 2, 2, '{}', 3, 'SUCCEEDED', 1, '00000000-0000-0000-0000-000000000002', 'old-worker',
					        now(), 0.9, '{"valAccuracy": 0.9}', now()),
					       (1, 3, 3, '{}', 3, 'FAILED', 1, '00000000-0000-0000-0000-000000000003', 'old-worker',
					        now(), NULL, NULL, now())
					""").update();

			Flyway.configure().dataSource(dataSource).load().migrate();

			// The stuck job now matches the recovery sweep's predicate...
			assertThat(jdbc.sql("SELECT job_index FROM jobs WHERE state = 'RUNNING' AND lease_expires_at <= now()")
				.query(Integer.class)
				.list()).containsExactly(1);
			// ...and has a RUNNING attempt row that recovery can mark EXPIRED. Every claimed job got a
			// row for its latest attempt, mirroring the job's state.
			assertThat(jdbc.sql("""
					SELECT id::text || ' ' || attempt_number || ' ' || status || ' ' || COALESCE(error_type, '-')
					       || ' ' || COALESCE(retryable::text, '-')
					FROM attempts ORDER BY job_id
					""").query(String.class).list()).containsExactly(
						"00000000-0000-0000-0000-000000000001 1 RUNNING - -",
						"00000000-0000-0000-0000-000000000002 1 SUCCEEDED - -",
						// A job failed by hand under V1 had no recorded reason; V3 marks it UNKNOWN and terminal.
						"00000000-0000-0000-0000-000000000003 1 FAILED UNKNOWN false");
		}
	}

}
