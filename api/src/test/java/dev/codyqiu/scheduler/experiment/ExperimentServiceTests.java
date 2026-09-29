package dev.codyqiu.scheduler.experiment;

import java.util.List;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.JobSpec;
import dev.codyqiu.scheduler.task.Task;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentServiceTests extends IntegrationTest {

	@Autowired
	ExperimentRepository experimentRepository;

	@Test
	void aJobRowThatFailsToInsertRollsBackTheWholeExperiment() {
		// Calls the service directly to skip HTTP validation: the negative seed on the last job
		// reaches the database and violates jobs_seed_nonnegative after the experiment row and
		// the first two jobs were already written in the same transaction.
		CreateExperimentRequest request = new CreateExperimentRequest("atomicity", Task.SYNTHETIC_MLP_V1, 3,
				List.of(new JobSpec(0, TestData.CONFIG), new JobSpec(1, TestData.CONFIG),
						new JobSpec(-1, TestData.CONFIG)));

		assertThatThrownBy(() -> experiments.create(request)).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("jobs_seed_nonnegative");

		assertThat(countRows("experiments")).isZero();
		assertThat(countRows("jobs")).isZero();
	}

	@Test
	void repositoryWritesRefuseToRunOutsideATransaction() {
		assertThatThrownBy(() -> experimentRepository.insert("orphan", Task.SYNTHETIC_MLP_V1, 3))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(countRows("experiments")).isZero();
	}

}
