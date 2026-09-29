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

	/** Must run inside the transaction that also inserts the experiment's jobs. */
	@Transactional(propagation = Propagation.MANDATORY)
	public long insert(String name, Task task, int maxAttempts) {
		return jdbc.sql("""
				INSERT INTO experiments (name, task, max_attempts)
				VALUES (:name, :task, :maxAttempts)
				RETURNING id
				""")
			.param("name", name)
			.param("task", task.id())
			.param("maxAttempts", maxAttempts)
			.query(Long.class)
			.single();
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
