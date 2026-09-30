package dev.codyqiu.scheduler.experiment;

import java.time.OffsetDateTime;
import java.util.Optional;

import dev.codyqiu.scheduler.experiment.ExperimentResponse.Progress;
import dev.codyqiu.scheduler.task.Task;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExperimentRepository {

	private final JdbcClient jdbc;

	public ExperimentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the experiment row unless another experiment already owns {@code idempotencyKey}.
	 * Must run inside the transaction that also inserts the experiment's jobs.
	 *
	 * <p>If a concurrent transaction has inserted the same key but not committed yet, PostgreSQL
	 * makes this statement wait for it. If that transaction commits, nothing is inserted here and
	 * the caller finds its row; if it rolls back, this insert proceeds. A null key never conflicts.
	 * @return the new experiment's id, or empty if the key was already taken
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Long> insert(String name, Task task, int maxAttempts, String idempotencyKey, String fingerprint) {
		return jdbc.sql("""
				INSERT INTO experiments (name, task, max_attempts, idempotency_key, request_fingerprint)
				VALUES (:name, :task, :maxAttempts, :idempotencyKey, :fingerprint)
				ON CONFLICT (idempotency_key) DO NOTHING
				RETURNING id
				""")
			.param("name", name)
			.param("task", task.id())
			.param("maxAttempts", maxAttempts)
			.param("idempotencyKey", idempotencyKey)
			.param("fingerprint", fingerprint)
			.query(Long.class)
			.optional();
	}

	/**
	 * The experiment that owns {@code idempotencyKey}. A new statement takes a new snapshot under
	 * READ COMMITTED, so this sees a row committed by the transaction an insert just waited for.
	 */
	Optional<StoredKey> findByIdempotencyKey(String idempotencyKey) {
		return jdbc.sql("SELECT id, request_fingerprint FROM experiments WHERE idempotency_key = :key")
			.param("key", idempotencyKey)
			.query((rs, rowNum) -> new StoredKey(rs.getLong("id"), rs.getString("request_fingerprint")))
			.optional();
	}

	/**
	 * Reads the experiment and its per-state job counts in a single statement. One statement
	 * reads one snapshot, so the counts agree with each other even while workers change jobs.
	 */
	public Optional<ExperimentResponse> findWithProgress(long id) {
		return jdbc.sql("""
				SELECT e.id, e.name, e.task, e.max_attempts, e.created_at,
				       count(j.id)                                     AS total,
				       count(j.id) FILTER (WHERE j.state = 'QUEUED')    AS queued,
				       count(j.id) FILTER (WHERE j.state = 'RUNNING')   AS running,
				       count(j.id) FILTER (WHERE j.state = 'SUCCEEDED') AS succeeded,
				       count(j.id) FILTER (WHERE j.state = 'FAILED')    AS failed
				FROM experiments e
				LEFT JOIN jobs j ON j.experiment_id = e.id
				WHERE e.id = :id
				GROUP BY e.id
				""")
			.param("id", id)
			.query((rs, rowNum) -> new ExperimentResponse(
					rs.getLong("id"),
					rs.getString("name"),
					Task.fromId(rs.getString("task")),
					rs.getInt("max_attempts"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					new Progress(rs.getInt("total"), rs.getInt("queued"), rs.getInt("running"),
							rs.getInt("succeeded"), rs.getInt("failed"))))
			.optional();
	}

	public boolean exists(long id) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM experiments WHERE id = :id)")
			.param("id", id)
			.query(Boolean.class)
			.single();
	}

}
