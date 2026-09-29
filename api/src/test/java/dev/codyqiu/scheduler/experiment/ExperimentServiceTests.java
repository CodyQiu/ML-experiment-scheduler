package dev.codyqiu.scheduler.experiment;

import java.util.List;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.job.JobSpec;
import dev.codyqiu.scheduler.task.Optimizer;
import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.Task;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentServiceTests extends IntegrationTest {

	private static final SyntheticMlpConfig CONFIG = new SyntheticMlpConfig(0.01, 32, 2, 64, 20, Optimizer.ADAM, 0.0);

	@Autowired
	ExperimentService experiments;

	@Autowired
	ExperimentRepository experimentRepository;

	@Test
	void aJobRowThatFailsToInsertRollsBackTheWholeExperiment() {
		// Calls the service directly to skip HTTP validation: the negative seed on the last job
		// reaches the database and violates jobs_seed_nonnegative after the experiment row and
		// the first two jobs were already written in the same transaction.
		CreateExperimentRequest request = new CreateExperimentRequest("atomicity", Task.SYNTHETIC_MLP_V1, 3,
				List.of(new JobSpec(0, CONFIG), new JobSpec(1, CONFIG), new JobSpec(-1, CONFIG)));

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
