package dev.codyqiu.scheduler.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
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
			       worker_id, val_accuracy, result, created_at, started_at, finished_at
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
				(result != null) ? jsonMapper.readTree(result) : null,
				instant(rs, "created_at"),
				instant(rs, "started_at"),
				instant(rs, "finished_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value != null) ? value.toInstant() : null;
	}

}
