package dev.codyqiu.scheduler.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import dev.codyqiu.scheduler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentApiTests extends IntegrationTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Test
	void validBatchCreatesTheExperimentAndOneQueuedJobPerConfig() throws Exception {
		MvcTestResult created = post(experiment(2, List.of(job(7, config(0.01)), job(8, config(0.1)), job(9, config(0.001)))));

		assertThat(created).hasStatus(HttpStatus.CREATED).headers().hasValue("Location", "/experiments/1");
		JsonNode experiment = body(created);
		assertThat(experiment.get("id").asLong()).isEqualTo(1);
		assertThat(experiment.get("name").asString()).isEqualTo("sweep");
		assertThat(experiment.get("task").asString()).isEqualTo("synthetic-mlp-v1");
		assertThat(experiment.get("maxAttempts").asInt()).isEqualTo(2);
		assertProgress(experiment, 3, 3, 0, 0, 0);
		assertThat(body(get("/experiments/1"))).isEqualTo(experiment);

		JsonNode jobs = body(get("/experiments/1/jobs")).get("jobs");
		assertThat(jobs.size()).isEqualTo(3);
		for (int i = 0; i < 3; i++) {
			JsonNode job = jobs.get(i);
			assertThat(job.get("experimentId").asLong()).isEqualTo(1);
			assertThat(job.get("jobIndex").asInt()).isEqualTo(i);
			assertThat(job.get("seed").asInt()).isEqualTo(7 + i);
			assertThat(job.get("state").asString()).isEqualTo("QUEUED");
			assertThat(job.get("attemptCount").asInt()).isZero();
			assertThat(job.get("maxAttempts").asInt()).isEqualTo(2);
			assertThat(job.get("workerId").isNull()).isTrue();
			assertThat(job.get("result").isNull()).isTrue();
		}
		assertThat(jobs.get(1).get("config")).isEqualTo(json.valueToTree(config(0.1)));

		long lastJobId = jobs.get(2).get("id").asLong();
		assertThat(body(get("/jobs/" + lastJobId))).isEqualTo(jobs.get(2));
	}

	@Test
	void maxAttemptsDefaultsToThree() throws Exception {
		Map<String, Object> request = experiment(1, List.of(job(0, config(0.01))));
		request.remove("maxAttempts");

		assertThat(body(post(request)).get("maxAttempts").asInt()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT max_attempts FROM jobs").query(Integer.class).single()).isEqualTo(3);
	}

	@Test
	void progressCountsEveryJobState() throws Exception {
		post(experiment(3, IntStream.range(0, 5).mapToObj(i -> job(i, config(0.01))).toList()));
		// Claim and completion endpoints do not exist yet, so move jobs through states directly.
		jdbc.sql("""
				UPDATE jobs
				SET state = 'RUNNING', attempt_count = 1, current_attempt_id = gen_random_uuid(),
				    worker_id = 'worker-a', started_at = now(), lease_expires_at = now() + interval '30 seconds'
				WHERE job_index IN (1, 2, 3)
				""").update();
		jdbc.sql("""
				UPDATE jobs
				SET state = 'SUCCEEDED', val_accuracy = 0.9, result = '{"valAccuracy": 0.9}', finished_at = now(),
				    lease_expires_at = NULL
				WHERE job_index = 2
				""").update();
		jdbc.sql("UPDATE jobs SET state = 'FAILED', finished_at = now(), lease_expires_at = NULL WHERE job_index = 3").update();

		assertProgress(body(get("/experiments/1")), 5, 2, 1, 1, 1);
	}

	@Test
	void everyConstraintViolationIsReportedAndNothingIsStored() throws Exception {
		Map<String, Object> tooFast = config(5.0);
		Map<String, Object> noEpochs = config(0.01);
		noEpochs.put("epochs", 0);
		List<Map<String, Object>> jobs = new ArrayList<>(List.of(job(0, config(0.01)), job(1, tooFast), job(2, noEpochs)));
		jobs.add(null);
		Map<String, Object> request = experiment(3, jobs);
		request.put("name", " ");

		JsonNode problem = assertProblem(post(request), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
		assertThat(fieldErrors(problem)).containsExactly(
				"jobs[1].config.learningRate: must be less than or equal to 1.0",
				"jobs[2].config.epochs: must be greater than or equal to 1",
				"jobs[3]: must not be null",
				"name: must not be blank");
		assertThat(countRows("experiments")).isZero();
		assertThat(countRows("jobs")).isZero();
	}

	@Test
	void misspelledHyperparameterIsRejectedNotIgnored() throws Exception {
		Map<String, Object> config = config(0.01);
		config.put("learningrate", 0.5);

		JsonNode problem = assertProblem(post(experiment(3, List.of(job(0, config)))), HttpStatus.BAD_REQUEST,
				"VALIDATION_FAILED");
		assertThat(fieldErrors(problem)).containsExactly("jobs[0].config.learningrate: unknown field");
	}

	@ParameterizedTest(name = "{0} = {1}")
	@MethodSource
	void wrongJsonTypesAreRejectedNotCoerced(String field, Object value, String expectedMessage) throws Exception {
		Map<String, Object> config = config(0.01);
		config.put(field, value);

		JsonNode problem = assertProblem(post(experiment(3, List.of(job(0, config)))), HttpStatus.BAD_REQUEST,
				"VALIDATION_FAILED");
		assertThat(fieldErrors(problem)).containsExactly("jobs[0].config." + field + ": " + expectedMessage);
	}

	static Stream<Arguments> wrongJsonTypesAreRejectedNotCoerced() {
		return Stream.of(
				Arguments.of("epochs", 20.5, "must be an integer"),
				Arguments.of("hiddenUnits", "64", "must be an integer"),
				Arguments.of("batchSize", true, "must be an integer"),
				Arguments.of("learningRate", "0.1", "must be a number"),
				Arguments.of("optimizer", "adamw", "must be one of [sgd, adam]"));
	}

	@Test
	void integerLiteralsAreAcceptedForDecimalFields() throws Exception {
		Map<String, Object> config = config(0.01);
		config.put("learningRate", 1);
		config.put("weightDecay", 0);

		assertThat(post(experiment(3, List.of(job(0, config))))).hasStatus(HttpStatus.CREATED);
	}

	@Test
	void unknownTaskIsRejected() throws Exception {
		Map<String, Object> request = experiment(3, List.of(job(0, config(0.01))));
		request.put("task", "mnist");

		JsonNode problem = assertProblem(post(request), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
		assertThat(fieldErrors(problem)).containsExactly("task: must be one of [synthetic-mlp-v1]");
	}

	@Test
	void batchMustContainBetweenOneAndFiveHundredJobs() throws Exception {
		for (int size : new int[] { 0, 501 }) {
			List<Map<String, Object>> jobs = IntStream.range(0, size).mapToObj(i -> job(i, config(0.01))).toList();
			JsonNode problem = assertProblem(post(experiment(3, jobs)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
			assertThat(fieldErrors(problem)).containsExactly("jobs: size must be between 1 and 500");
		}
		assertThat(countRows("jobs")).isZero();

		List<Map<String, Object>> largest = IntStream.range(0, 500).mapToObj(i -> job(i, config(0.01))).toList();
		assertProgress(body(post(experiment(3, largest))), 500, 500, 0, 0, 0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "{\"name\": ", "[]", "{\"name\": \"a\", \"name\": \"b\"}" })
	void malformedBodiesAreRejected(String body) throws Exception {
		MvcTestResult result = mvc.post().uri("/experiments").contentType(MediaType.APPLICATION_JSON).content(body).exchange();

		assertProblem(result, HttpStatus.BAD_REQUEST, "MALFORMED_JSON");
		assertThat(countRows("experiments")).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/experiments/999", "/experiments/999/jobs", "/jobs/999" })
	void unknownIdsReturnNotFound(String path) throws Exception {
		JsonNode problem = assertProblem(get(path), HttpStatus.NOT_FOUND, "NOT_FOUND");
		assertThat(problem.get("detail").asString()).endsWith(" 999 does not exist");
	}

	@Test
	void theListShowsTheNewestExperimentsFirstEachAsItsOwnEndpointShowsIt() throws Exception {
		for (int size = 1; size <= 3; size++) {
			post(experiment(2, IntStream.range(0, size).mapToObj(i -> job(i, config(0.01))).toList()));
		}
		// Jobs of experiment 3 in every state, so the list's counts are checked beyond "all queued".
		jdbc.sql("""
				UPDATE jobs
				SET state = 'RUNNING', attempt_count = 1, current_attempt_id = gen_random_uuid(),
				    worker_id = 'worker-a', started_at = now(), lease_expires_at = now() + interval '30 seconds'
				WHERE experiment_id = 3 AND job_index IN (1, 2)
				""").update();
		jdbc.sql("""
				UPDATE jobs
				SET state = 'SUCCEEDED', val_accuracy = 0.9, result = '{"valAccuracy": 0.9}', finished_at = now(),
				    lease_expires_at = NULL
				WHERE experiment_id = 3 AND job_index = 2
				""").update();

		JsonNode experiments = body(get("/experiments")).get("experiments");

		assertThat(ids(experiments)).containsExactly(3L, 2L, 1L);
		assertProgress(experiments.get(0), 3, 1, 1, 1, 0);
		for (JsonNode listed : experiments) {
			assertThat(listed).isEqualTo(body(get("/experiments/" + listed.get("id").asLong())));
		}
	}

	@Test
	void theListIsEmptyWithoutExperimentsAndHonorsItsLimit() throws Exception {
		assertThat(body(get("/experiments")).get("experiments").size()).isZero();
		for (int i = 0; i < 3; i++) {
			post(experiment(1, List.of(job(0, config(0.01)))));
		}

		assertThat(ids(body(get("/experiments?limit=2")).get("experiments"))).containsExactly(3L, 2L);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "101" })
	void theListLimitMustBeBetweenOneAndOneHundred(String limit) throws Exception {
		JsonNode problem = assertProblem(get("/experiments?limit=" + limit), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

		assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("limit");
	}

	@Test
	void frameworkErrorsUseTheSameProblemFormat() throws Exception {
		assertProblem(mvc.delete().uri("/experiments/1").exchange(), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
		assertProblem(get("/experiments/not-a-number"), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
	}

	private MvcTestResult post(Map<String, Object> body) {
		return mvc.post()
			.uri("/experiments")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(body))
			.exchange();
	}

	private MvcTestResult get(String path) {
		return mvc.get().uri(path).exchange();
	}

	private JsonNode body(MvcTestResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode assertProblem(MvcTestResult result, HttpStatus status, String code) throws Exception {
		assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		JsonNode problem = body(result);
		assertThat(problem.get("status").asInt()).isEqualTo(status.value());
		assertThat(problem.get("code").asString()).isEqualTo(code);
		return problem;
	}

	private static List<Long> ids(JsonNode experiments) {
		List<Long> ids = new ArrayList<>();
		experiments.forEach((experiment) -> ids.add(experiment.get("id").asLong()));
		return ids;
	}

	private static List<String> fieldErrors(JsonNode problem) {
		List<String> errors = new ArrayList<>();
		for (JsonNode error : problem.get("errors")) {
			errors.add(error.get("field").asString() + ": " + error.get("message").asString());
		}
		return errors;
	}

	private static void assertProgress(JsonNode experiment, int total, int queued, int running, int succeeded,
			int failed) {
		JsonNode progress = experiment.get("progress");
		assertThat(List.of(progress.get("total").asInt(), progress.get("queued").asInt(),
				progress.get("running").asInt(), progress.get("succeeded").asInt(), progress.get("failed").asInt()))
			.containsExactly(total, queued, running, succeeded, failed);
	}

	private static Map<String, Object> experiment(int maxAttempts, List<Map<String, Object>> jobs) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("name", "sweep");
		request.put("task", "synthetic-mlp-v1");
		request.put("maxAttempts", maxAttempts);
		request.put("jobs", jobs);
		return request;
	}

	private static Map<String, Object> job(int seed, Map<String, Object> config) {
		Map<String, Object> job = new LinkedHashMap<>();
		job.put("seed", seed);
		job.put("config", config);
		return job;
	}

	private static Map<String, Object> config(double learningRate) {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("learningRate", learningRate);
		config.put("hiddenUnits", 32);
		config.put("hiddenLayers", 2);
		config.put("batchSize", 64);
		config.put("epochs", 20);
		config.put("optimizer", "adam");
		config.put("weightDecay", 0.0001);
		return config;
	}

}
