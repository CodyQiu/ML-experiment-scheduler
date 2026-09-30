package dev.codyqiu.scheduler.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attempt history. Each write here accompanies a guarded write to the job in the same
 * transaction, so a job and its current attempt always change together. When the expected rows
 * do not match, jobs and attempts have diverged; the method throws, rolling back both writes.
 */
@Repository
public class AttemptRepository {

	private final JdbcClient jdbc;

	public AttemptRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void insertRunning(JobAssignment claimed, String workerId) {
		jdbc.sql("""
				INSERT INTO attempts (id, job_id, attempt_number, worker_id, claimed_at)
				VALUES (:id, :jobId, :attemptNumber, :workerId, now())
				""")
			.param("id", claimed.attemptId())
			.param("jobId", claimed.jobId())
			.param("attemptNumber", claimed.attemptNumber())
			.param("workerId", workerId)
			.update();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void recordHeartbeat(UUID attemptId) {
		requireRows(1, jdbc.sql("UPDATE attempts SET last_heartbeat_at = now() WHERE id = :id AND status = 'RUNNING'")
			.param("id", attemptId)
			.update(), attemptId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void markSucceeded(UUID attemptId) {
		requireRows(1, jdbc.sql("""
				UPDATE attempts SET status = 'SUCCEEDED', finished_at = now()
				WHERE id = :id AND status = 'RUNNING'
				""")
			.param("id", attemptId)
			.update(), attemptId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void markFailed(UUID attemptId, String errorType, String errorMessage, boolean retryable) {
		requireRows(1, jdbc.sql("""
				UPDATE attempts
				SET status = 'FAILED', finished_at = now(), error_type = :errorType, error_message = :errorMessage,
				    retryable = :retryable
				WHERE id = :id AND status = 'RUNNING'
				""")
			.param("errorType", errorType)
			.param("errorMessage", errorMessage)
			.param("retryable", retryable)
			.param("id", attemptId)
			.update(), attemptId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void markExpired(List<UUID> attemptIds) {
		requireRows(attemptIds.size(), jdbc.sql("""
				UPDATE attempts
				SET status = 'EXPIRED', finished_at = now(), error_type = 'LEASE_EXPIRED',
				    error_message = 'lease expired before the attempt reported a result'
				WHERE id IN (:ids) AND status = 'RUNNING'
				""")
			.param("ids", attemptIds)
			.update(), attemptIds);
	}

	public List<AttemptResponse> findByJobId(long jobId) {
		return jdbc.sql("""
				SELECT attempt_number, worker_id, status, claimed_at, last_heartbeat_at, finished_at,
				       error_type, error_message, retryable
				FROM attempts
				WHERE job_id = :jobId
				ORDER BY attempt_number
				""")
			.param("jobId", jobId)
			.query((rs, rowNum) -> new AttemptResponse(rs.getInt("attempt_number"), rs.getString("worker_id"),
					AttemptStatus.valueOf(rs.getString("status")), instant(rs, "claimed_at"),
					instant(rs, "last_heartbeat_at"), instant(rs, "finished_at"), rs.getString("error_type"),
					rs.getString("error_message"), rs.getObject("retryable", Boolean.class)))
			.list();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value != null) ? value.toInstant() : null;
	}

	private static void requireRows(int expected, int actual, Object attempts) {
		if (actual != expected) {
			throw new IllegalStateException(
					"Expected %d running attempt row(s) for %s but updated %d".formatted(expected, attempts, actual));
		}
	}

}
