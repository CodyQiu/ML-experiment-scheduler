package dev.codyqiu.scheduler.job;

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

	private static void requireRows(int expected, int actual, Object attempts) {
		if (actual != expected) {
			throw new IllegalStateException(
					"Expected %d running attempt row(s) for %s but updated %d".formatted(expected, attempts, actual));
		}
	}

}
