package dev.codyqiu.scheduler.experiment;

import java.util.ArrayList;
import java.util.List;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.worker.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

class BestJobsApiTests extends IntegrationTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	WorkerService workers;

	@Test
	void onlySuccessfulJobsAreRankedByValidationAccuracyWithTiesInBatchOrder() throws Exception {
		List<Long> jobIds = submitJobs(5);
		succeed(0.70);                                   // job 0
		succeed(0.90);                                   // job 1
		JobAssignment failing = workers.claim("worker-1").orElseThrow();   // job 2
		workers.fail(failing.jobId(), failing.attemptId(), false, "TRAINING_DIVERGED", "nan");
		succeed(0.90);                                   // job 3: ties with job 1
		workers.claim("worker-1").orElseThrow();         // job 4 stays RUNNING

		JsonNode best = body(mvc.get().uri("/experiments/1/best").exchange(), HttpStatus.OK);

		assertThat(best.get("experimentId").asLong()).isEqualTo(1);
		assertThat(best.get("metric").asString()).isEqualTo("valAccuracy");
		List<String> ranking = new ArrayList<>();
		for (JsonNode job : best.get("jobs")) {
			ranking.add(job.get("rank").asInt() + ":" + job.get("jobId").asLong() + "@" + job.get("valAccuracy").asDouble());
		}
		assertThat(ranking).containsExactly("1:%d@0.9".formatted(jobIds.get(1)), "2:%d@0.9".formatted(jobIds.get(3)),
				"3:%d@0.7".formatted(jobIds.get(0)));
		JsonNode top = best.get("jobs").get(0);
		assertThat(top.get("config")).isEqualTo(json.valueToTree(TestData.CONFIG));
		assertThat(top.get("metrics")).isEqualTo(json.valueToTree(TestData.metrics(0.9)));
	}

	@Test
	void limitCapsTheListAndAnExperimentWithoutSuccessesHasAnEmptyOne() throws Exception {
		submitJobs(3);
		succeed(0.5);
		succeed(0.6);
		succeed(0.7);
		assertThat(body(mvc.get().uri("/experiments/1/best?limit=2").exchange(), HttpStatus.OK).get("jobs").size())
			.isEqualTo(2);

		submitJobs(1);
		assertThat(body(mvc.get().uri("/experiments/2/best").exchange(), HttpStatus.OK).get("jobs").size()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "101" })
	void limitMustBeBetweenOneAndOneHundred(String limit) throws Exception {
		submitJobs(1);

		JsonNode problem = body(mvc.get().uri("/experiments/1/best?limit=" + limit).exchange(), HttpStatus.BAD_REQUEST);

		assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("limit");
	}

	@Test
	void unknownExperimentsAreNotFound() {
		assertThat(mvc.get().uri("/experiments/999/best").exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

	/** Claims the next queued job and reports it successful with the given accuracy. */
	private void succeed(double valAccuracy) {
		JobAssignment assignment = workers.claim("worker-1").orElseThrow();
		workers.complete(assignment.jobId(), assignment.attemptId(), TestData.metrics(valAccuracy));
	}

	private JsonNode body(MvcTestResult result, HttpStatus expectedStatus) throws Exception {
		assertThat(result).hasStatus(expectedStatus);
		return json.readTree(result.getResponse().getContentAsString());
	}

}
