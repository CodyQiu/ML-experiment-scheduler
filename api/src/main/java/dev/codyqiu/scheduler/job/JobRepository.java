package dev.codyqiu.scheduler.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.codyqiu.scheduler.lease.SchedulerProperties.Lease;
import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.Task;
import dev.codyqiu.scheduler.task.TrainingMetrics;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobRepository {

	private static final String SELECT_JOBS = """
			SELECT id, experiment_id, job_index, seed, config, state, attempt_count, max_attempts,
			       worker_id, val_accuracy, result, created_at, started_at, finished_at, lease_expires_at
			FROM jobs
			""";

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	private final JsonMapper jsonMapper;

	public JobRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Inserts every job of a new experiment in one JDBC batch. {@code MANDATORY} makes this fail
	 * unless the caller already opened the transaction that also inserted the experiment row.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void insertAll(long experimentId, int maxAttempts, List<JobSpec> jobs) {
		List<Object[]> rows = new ArrayList<>(jobs.size());
		for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
			JobSpec job = jobs.get(jobIndex);
			String configJson = jsonMapper.writeValueAsString(job.config());
			rows.add(new Object[] { experimentId, jobIndex, job.seed(), configJson, maxAttempts });
		}
		jdbcTemplate.batchUpdate("""
				INSERT INTO jobs (experiment_id, job_index, seed, config, max_attempts)
				VALUES (?, ?, ?, CAST(? AS jsonb), ?)
				""", rows);
	}

	/**
	 * Claims the oldest queued job for {@code workerId}, starts a new attempt of it, and grants that
	 * attempt a lease. Returns empty when no queued job can be locked at this moment.
	 *
	 * <p>This is one statement. The CTE locks a single queued row; {@code SKIP LOCKED} makes it pass
	 * over rows that concurrent claims have already locked instead of waiting for them. The UPDATE
	 * then moves the locked row to RUNNING under a freshly generated attempt id. Because a row lock
	 * has exactly one holder, two concurrent claims can never return the same job. The caller's
	 * transaction also records the attempt row.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<JobAssignment> claimNext(String workerId, Lease lease) {
		return jdbc.sql("""
				WITH next_job AS (
				    SELECT id
				    FROM jobs
				    WHERE state = 'QUEUED'
				    ORDER BY id
				    LIMIT 1
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE jobs j
				SET state = 'RUNNING',
				    attempt_count = j.attempt_count + 1,
				    current_attempt_id = gen_random_uuid(),
				    worker_id = :workerId,
				    started_at = now(),
				    lease_expires_at = now() + make_interval(secs => :leaseSeconds)
				FROM next_job, experiments e
				WHERE j.id = next_job.id
				  AND e.id = j.experiment_id
				RETURNING j.id, j.current_attempt_id, j.attempt_count, j.experiment_id, e.task, j.seed, j.config,
				          j.lease_expires_at
				""")
			.param("workerId", workerId)
			.param("leaseSeconds", lease.durationSeconds())
			.query((rs, rowNum) -> new JobAssignment(
					rs.getLong("id"),
					rs.getObject("current_attempt_id", UUID.class),
					rs.getInt("attempt_count"),
					rs.getLong("experiment_id"),
					Task.fromId(rs.getString("task")),
					rs.getInt("seed"),
					jsonMapper.readValue(rs.getString("config"), SyntheticMlpConfig.class),
					instant(rs, "lease_expires_at"),
					lease.durationSeconds(),
					lease.heartbeatIntervalSeconds()))
			.optional();
	}

	/**
	 * Extends the lease if, and only if, {@code attemptId} is the running attempt of the job and
	 * its lease has not yet passed. The strict check means an expired attempt cannot revive itself,
	 * even before recovery has noticed the expiry.
	 * @return the new expiry (database time), or empty if the renewal was rejected
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Instant> renewLease(long jobId, UUID attemptId, Lease lease) {
		return jdbc.sql("""
				UPDATE jobs
				SET lease_expires_at = now() + make_interval(secs => :leaseSeconds)
				WHERE id = :jobId
				  AND state = 'RUNNING'
				  AND current_attempt_id = :attemptId
				  AND lease_expires_at > now()
				RETURNING lease_expires_at
				""")
			.param("leaseSeconds", lease.durationSeconds())
			.param("jobId", jobId)
			.param("attemptId", attemptId)
			.query((rs, rowNum) -> instant(rs, "lease_expires_at"))
			.optional();
	}

	/**
	 * Records a successful result if, and only if, {@code attemptId} is the running attempt of the
	 * job and still holds an unexpired lease. The WHERE clause is the entire authorization check,
	 * and it is evaluated atomically with the write: if another transaction changed the row first,
	 * PostgreSQL waits for that change to commit and re-checks the clause against the new row
	 * before writing.
	 * @return {@code true} if the result was accepted (one row updated); {@code false} otherwise
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean markSucceeded(long jobId, UUID attemptId, TrainingMetrics metrics) {
		int updated = jdbc.sql("""
				UPDATE jobs
				SET state = 'SUCCEEDED',
				    val_accuracy = :valAccuracy,
				    result = CAST(:result AS jsonb),
				    finished_at = now(),
				    lease_expires_at = NULL
				WHERE id = :jobId
				  AND state = 'RUNNING'
				  AND current_attempt_id = :attemptId
				  AND lease_expires_at > now()
				""")
			.param("valAccuracy", metrics.valAccuracy())
			.param("result", jsonMapper.writeValueAsString(metrics))
			.param("jobId", jobId)
			.param("attemptId", attemptId)
			.update();
		return updated == 1;
	}

	/** Explains an already-rejected request by {@code attemptId}. Never used to decide a write. */
	public Optional<AttemptStanding> findStanding(long jobId, UUID attemptId) {
		return jdbc.sql("""
				SELECT state,
				       COALESCE(current_attempt_id = :attemptId, false) AS is_current,
				       COALESCE(lease_expires_at <= now(), false) AS lease_expired
				FROM jobs
				WHERE id = :jobId
				""")
			.param("jobId", jobId)
			.param("attemptId", attemptId)
			.query((rs, rowNum) -> new AttemptStanding(JobState.valueOf(rs.getString("state")),
					rs.getBoolean("is_current"), rs.getBoolean("lease_expired")))
			.optional();
	}

	/**
	 * Locks up to {@code limit} RUNNING jobs whose lease has passed, oldest expiry first. Rows that
	 * another transaction holds (a completion or heartbeat in flight, or another sweep) are skipped
	 * rather than waited for. The locks last until the caller's transaction ends, so nothing can
	 * change these rows before they are recovered.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<ExpiredLease> lockExpiredLeases(int limit) {
		return jdbc.sql("""
				SELECT id, current_attempt_id, attempt_count, max_attempts, worker_id
				FROM jobs
				WHERE state = 'RUNNING'
				  AND lease_expires_at <= now()
				ORDER BY lease_expires_at
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
				""")
			.param("limit", limit)
			.query((rs, rowNum) -> new ExpiredLease(rs.getLong("id"), rs.getObject("current_attempt_id", UUID.class),
					rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getString("worker_id")))
			.list();
	}

	/** Returns locked, expired jobs that still have attempts left to the queue. */
	@Transactional(propagation = Propagation.MANDATORY)
	public int requeue(List<Long> jobIds) {
		return jdbc.sql("""
				UPDATE jobs
				SET state = 'QUEUED', current_attempt_id = NULL, worker_id = NULL, started_at = NULL,
				    lease_expires_at = NULL
				WHERE id IN (:jobIds)
				  AND state = 'RUNNING'
				  AND attempt_count < max_attempts
				""")
			.param("jobIds", jobIds)
			.update();
	}

	/** Fails locked, expired jobs whose final attempt this was. They keep their last attempt's identity. */
	@Transactional(propagation = Propagation.MANDATORY)
	public int failExhausted(List<Long> jobIds) {
		return jdbc.sql("""
				UPDATE jobs
				SET state = 'FAILED', lease_expires_at = NULL, finished_at = now()
				WHERE id IN (:jobIds)
				  AND state = 'RUNNING'
				  AND attempt_count >= max_attempts
				""")
			.param("jobIds", jobIds)
			.update();
	}

	public Optional<JobResponse> findById(long id) {
		return jdbc.sql(SELECT_JOBS + "WHERE id = :id")
			.param("id", id)
			.query(this::mapJob)
			.optional();
	}

	public List<JobResponse> findByExperimentId(long experimentId) {
		return jdbc.sql(SELECT_JOBS + "WHERE experiment_id = :experimentId ORDER BY job_index")
			.param("experimentId", experimentId)
			.query(this::mapJob)
			.list();
	}

	private JobResponse mapJob(ResultSet rs, int rowNum) throws SQLException {
		String result = rs.getString("result");
		return new JobResponse(
				rs.getLong("id"),
				rs.getLong("experiment_id"),
				rs.getInt("job_index"),
				rs.getInt("seed"),
				jsonMapper.readValue(rs.getString("config"), SyntheticMlpConfig.class),
				JobState.valueOf(rs.getString("state")),
				rs.getInt("attempt_count"),
				rs.getInt("max_attempts"),
				rs.getString("worker_id"),
				rs.getObject("val_accuracy", Double.class),
				(result != null) ? jsonMapper.readValue(result, TrainingMetrics.class) : null,
				instant(rs, "created_at"),
				instant(rs, "started_at"),
				instant(rs, "finished_at"),
				instant(rs, "lease_expires_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value != null) ? value.toInstant() : null;
	}

}
