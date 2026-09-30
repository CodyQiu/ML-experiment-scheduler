package dev.codyqiu.scheduler;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import dev.codyqiu.scheduler.experiment.CreateExperimentRequest;
import dev.codyqiu.scheduler.experiment.ExperimentService;
import dev.codyqiu.scheduler.job.JobSpec;
import dev.codyqiu.scheduler.task.Task;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base class for tests against a real PostgreSQL container. All subclasses share one Spring
 * context and therefore one container; each test starts from empty tables with ids from 1.
 *
 * <p>The periodic recovery sweep is off, so a lease that a test expires stays expired until the
 * test runs recovery itself. A background sweep would make those tests race.
 */
@SpringBootTest(properties = "scheduler.recovery.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected ExperimentService experiments;

	@BeforeEach
	void resetDatabase() {
		jdbc.sql("TRUNCATE experiments, jobs, attempts RESTART IDENTITY").update();
	}

	protected int countRows(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
	}

	/** Submits an experiment of {@code jobCount} jobs and returns their ids in claim (FIFO) order. */
	protected List<Long> submitJobs(int jobCount) {
		return submitJobs(jobCount, 3);
	}

	protected List<Long> submitJobs(int jobCount, int maxAttempts) {
		List<JobSpec> jobs = IntStream.range(0, jobCount).mapToObj(seed -> new JobSpec(seed, TestData.CONFIG)).toList();
		long experimentId = experiments
			.create(new CreateExperimentRequest("test", Task.SYNTHETIC_MLP_V1, maxAttempts, jobs))
			.id();
		return jdbc.sql("SELECT id FROM jobs WHERE experiment_id = :id ORDER BY id")
			.param("id", experimentId)
			.query(Long.class)
			.list();
	}

	/** Moves a running job's lease into the past, as if its worker had stopped heartbeating. */
	protected void expireLease(long jobId) {
		int updated = jdbc.sql("""
				UPDATE jobs SET lease_expires_at = now() - interval '1 second'
				WHERE id = :id AND state = 'RUNNING'
				""").param("id", jobId).update();
		if (updated != 1) {
			throw new IllegalStateException("Job " + jobId + " is not running");
		}
	}

	/** Seconds until the job's lease expires, measured on the database clock like the guards do. */
	protected double leaseSecondsRemaining(long jobId) {
		return jdbc.sql("SELECT EXTRACT(EPOCH FROM lease_expires_at - now()) FROM jobs WHERE id = :id")
			.param("id", jobId)
			.query(Double.class)
			.single();
	}

	protected Instant storedLease(long jobId) {
		return jdbc.sql("SELECT lease_expires_at FROM jobs WHERE id = :id")
			.param("id", jobId)
			.query(OffsetDateTime.class)
			.optional()
			.map(OffsetDateTime::toInstant)
			.orElse(null);
	}

	protected String attemptStatus(Object attemptId) {
		return jdbc.sql("SELECT status FROM attempts WHERE id = :id").param("id", attemptId).query(String.class).single();
	}

}
