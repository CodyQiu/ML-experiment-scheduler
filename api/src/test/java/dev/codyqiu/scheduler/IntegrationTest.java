package dev.codyqiu.scheduler;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base class for tests against a real PostgreSQL container. All subclasses share one Spring
 * context and therefore one container; each test starts from empty tables with ids from 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	@Autowired
	protected JdbcClient jdbc;

	@BeforeEach
	void resetDatabase() {
		jdbc.sql("TRUNCATE experiments, jobs RESTART IDENTITY").update();
	}

	protected int countRows(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
	}

}
