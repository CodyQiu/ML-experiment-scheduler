package dev.codyqiu.scheduler.experiment;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import dev.codyqiu.scheduler.Concurrently;
import dev.codyqiu.scheduler.IntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/** Idempotency-Key on POST /experiments, against real PostgreSQL. */
class IdempotencyTests extends IntegrationTest {

	private static final String BATCH = """
			{"name": "sweep", "task": "synthetic-mlp-v1", "maxAttempts": 3, "jobs": [
			  {"seed": 0, "config": {"learningRate": 0.0001, "hiddenUnits": 16, "hiddenLayers": 1, "batchSize": 64,
			                         "epochs": 20, "optimizer": "adam", "weightDecay": 0.0}},
			  {"seed": 1, "config": {"learningRate": 0.01, "hiddenUnits": 32, "hiddenLayers": 2, "batchSize": 64,
			                         "epochs": 20, "optimizer": "sgd", "weightDecay": 0.001}}]}
			""";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Test
	void aRepeatedSubmissionReturnsTheOriginalExperimentAndCreatesNothing() throws Exception {
		MvcTestResult first = submit(BATCH, "key-1");
		assertThat(first).hasStatus(HttpStatus.CREATED);
		long experimentId = body(first).get("id").asLong();

		MvcTestResult again = submit(BATCH, "key-1");

		assertThat(again).hasStatus(HttpStatus.OK).headers().hasValue("Idempotent-Replayed", "true");
		assertThat(body(again).get("id").asLong()).isEqualTo(experimentId);
		assertThat(countRows("experiments")).isOne();
		assertThat(countRows("jobs")).isEqualTo(2);
	}

	@Test
	void keyOrderNumberSpellingAndOmittedDefaultsDoNotChangeARequestsIdentity() throws Exception {
		assertThat(submit(BATCH, "key-1")).hasStatus(HttpStatus.CREATED);
		// Same meaning as BATCH: keys reordered, 0.0001 written as 1e-4, 0.01 as 1.0E-2, 0.001 as
		// 0.0010, 0.0 as 0, and maxAttempts omitted because its default is 3.
		String sameMeaning = """
				{"jobs": [
				  {"config": {"weightDecay": 0, "epochs": 20, "optimizer": "adam", "batchSize": 64, "hiddenLayers": 1,
				              "hiddenUnits": 16, "learningRate": 1e-4}, "seed": 0},
				  {"seed": 1, "config": {"optimizer": "sgd", "learningRate": 1.0E-2, "hiddenUnits": 32, "hiddenLayers": 2,
				                         "batchSize": 64, "epochs": 20, "weightDecay": 0.0010}}],
				 "task": "synthetic-mlp-v1", "name": "sweep"}
				""";

		assertThat(submit(sameMeaning, "key-1")).hasStatus(HttpStatus.OK);
		assertThat(countRows("experiments")).isOne();
	}

	@Test
	void aKeyReusedForADifferentRequestIsAConflictAndCreatesNothing() throws Exception {
		long experimentId = body(submit(BATCH, "key-1")).get("id").asLong();

		MvcTestResult conflict = submit(BATCH.replace("\"learningRate\": 0.01", "\"learningRate\": 0.02"), "key-1");

		assertThat(conflict).hasStatus(HttpStatus.CONFLICT);
		JsonNode problem = body(conflict);
		assertThat(problem.get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		assertThat(problem.get("experimentId").asLong()).isEqualTo(experimentId);
		assertThat(countRows("experiments")).isOne();
		assertThat(countRows("jobs")).isEqualTo(2);
	}

	@Test
	void jobOrderIsPartOfTheRequestBecauseItDecidesJobIndexes() throws Exception {
		assertThat(submit(BATCH, "key-1")).hasStatus(HttpStatus.CREATED);
		ObjectNode reversed = (ObjectNode) json.readTree(BATCH);
		JsonNode jobs = reversed.get("jobs");
		reversed.putArray("jobs").add(jobs.get(1)).add(jobs.get(0));

		assertThat(submit(json.writeValueAsString(reversed), "key-1")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void differentKeysOrNoKeyCreateSeparateExperiments() throws Exception {
		assertThat(submit(BATCH, "key-1")).hasStatus(HttpStatus.CREATED);
		assertThat(submit(BATCH, "key-2")).hasStatus(HttpStatus.CREATED);
		assertThat(submit(BATCH, null)).hasStatus(HttpStatus.CREATED);
		assertThat(submit(BATCH, null)).hasStatus(HttpStatus.CREATED);

		assertThat(countRows("experiments")).isEqualTo(4);
	}

	@ParameterizedTest
	@MethodSource
	void malformedKeysAreRejectedBeforeAnythingIsStored(String key) throws Exception {
		MvcTestResult result = submit(BATCH, key);

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		JsonNode error = body(result).get("errors").get(0);
		assertThat(error.get("field").asString()).isEqualTo("Idempotency-Key");
		assertThat(countRows("experiments")).isZero();
	}

	static Stream<String> malformedKeysAreRejectedBeforeAnythingIsStored() {
		return Stream.of("has space", "x".repeat(256), "tab\there");
	}

	@RepeatedTest(3)
	void concurrentSubmissionsWithOneKeyCreateExactlyOneExperiment() throws Exception {
		CreateExperimentRequest request = json.readValue(BATCH, CreateExperimentRequest.class);

		List<Submission> outcomes = Concurrently.runTogether(IntStream.range(0, 8)
			.mapToObj(i -> (Callable<Submission>) () -> experiments.submit(request, "key-1"))
			.toList());

		assertThat(outcomes).filteredOn(Submission.Created.class::isInstance).hasSize(1);
		assertThat(outcomes).filteredOn(Submission.Replayed.class::isInstance).hasSize(7);
		assertThat(outcomes).extracting(IdempotencyTests::experimentId).containsOnly(experimentId(outcomes.get(0)));
		assertThat(countRows("experiments")).isOne();
		assertThat(countRows("jobs")).isEqualTo(2);
	}

	@RepeatedTest(3)
	void concurrentSubmissionsWithOneKeyButDifferentBodiesCreateOneExperiment() throws Exception {
		CreateExperimentRequest a = json.readValue(BATCH, CreateExperimentRequest.class);
		CreateExperimentRequest b = json.readValue(BATCH.replace("\"name\": \"sweep\"", "\"name\": \"other\""),
				CreateExperimentRequest.class);
		List<CreateExperimentRequest> requests = IntStream.range(0, 8).mapToObj(i -> (i % 2 == 0) ? a : b).toList();

		List<Submission> outcomes = Concurrently.runTogether(requests.stream()
			.map(request -> (Callable<Submission>) () -> experiments.submit(request, "key-1"))
			.toList());

		// One request won. Every other request with the winner's body replays it; every request with
		// the other body is told the key is taken.
		int winner = IntStream.range(0, 8).filter(i -> outcomes.get(i) instanceof Submission.Created).findFirst().orElseThrow();
		for (int i = 0; i < 8; i++) {
			if (i == winner) {
				continue;
			}
			Class<?> expected = (requests.get(i) == requests.get(winner)) ? Submission.Replayed.class
					: Submission.KeyReused.class;
			assertThat(outcomes.get(i)).isInstanceOf(expected);
		}
		assertThat(countRows("experiments")).isOne();
	}

	private static long experimentId(Submission submission) {
		return switch (submission) {
			case Submission.Created(ExperimentResponse experiment) -> experiment.id();
			case Submission.Replayed(ExperimentResponse experiment) -> experiment.id();
			case Submission.KeyReused(long experimentId) -> experimentId;
		};
	}

	private MvcTestResult submit(String body, String key) {
		MockMvcRequestBuilder request = mvc.post().uri("/experiments").contentType(MediaType.APPLICATION_JSON).content(body);
		if (key != null) {
			request = request.header("Idempotency-Key", key);
		}
		return request.exchange();
	}

	private JsonNode body(MvcTestResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString());
	}

}
