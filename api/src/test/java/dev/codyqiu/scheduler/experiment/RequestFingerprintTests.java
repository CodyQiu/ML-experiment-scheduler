package dev.codyqiu.scheduler.experiment;

import java.util.List;

import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.JobSpec;
import dev.codyqiu.scheduler.task.Optimizer;
import dev.codyqiu.scheduler.task.SyntheticMlpConfig;
import dev.codyqiu.scheduler.task.Task;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The fingerprint alone, without Spring or a database. */
class RequestFingerprintTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void isAVersionedSha256() {
		assertThat(RequestFingerprint.of(request("sweep", 3, List.of(job(0, 0.01)))))
			.matches("v1:[0-9a-f]{64}");
	}

	@Test
	void appliesDefaultsBeforeComparing() {
		assertThat(RequestFingerprint.of(request("sweep", null, List.of(job(0, 0.01)))))
			.isEqualTo(RequestFingerprint.of(request("sweep", 3, List.of(job(0, 0.01)))));
	}

	@Test
	void comparesParsedNumbersNotTheirSpelling() {
		String plain = body("0.0001", "0.0");
		String exotic = body("1.0E-4", "0");

		assertThat(RequestFingerprint.of(JSON.readValue(plain, CreateExperimentRequest.class)))
			.isEqualTo(RequestFingerprint.of(JSON.readValue(exotic, CreateExperimentRequest.class)));
	}

	@Test
	void changesWithEveryMeaningfulDifference() {
		String base = RequestFingerprint.of(request("sweep", 3, List.of(job(0, 0.01), job(1, 0.1))));

		assertThat(List.of(
				RequestFingerprint.of(request("sweep-2", 3, List.of(job(0, 0.01), job(1, 0.1)))),
				RequestFingerprint.of(request("sweep", 2, List.of(job(0, 0.01), job(1, 0.1)))),
				RequestFingerprint.of(request("sweep", 3, List.of(job(0, 0.01), job(1, 0.2)))),
				RequestFingerprint.of(request("sweep", 3, List.of(job(0, 0.01), job(2, 0.1)))),
				RequestFingerprint.of(request("sweep", 3, List.of(job(1, 0.1), job(0, 0.01))))))
			.doesNotContain(base)
			.doesNotHaveDuplicates();
	}

	@Test
	void theCanonicalFormIsPinnedSoStoredFingerprintsStayValid() {
		// Fingerprints are stored with their keys and compared when a request is retried, possibly
		// after a deploy. This value was recorded from the v1 implementation. If it changes, the
		// canonical form changed (a serializer upgrade, a reordered record, a new number format),
		// and VERSION must be bumped; existing keys would then stop matching their retries.
		String golden = """
				{"name": "golden", "task": "synthetic-mlp-v1", "maxAttempts": 2, "jobs": [{"seed": 7, "config": {
				  "learningRate": 0.0001, "hiddenUnits": 64, "hiddenLayers": 2, "batchSize": 32, "epochs": 20,
				  "optimizer": "sgd", "weightDecay": 0.001}}]}
				""";

		assertThat(RequestFingerprint.of(JSON.readValue(golden, CreateExperimentRequest.class)))
			.isEqualTo("v1:9e1a0d4bd9b2266baf27bf6b90a872887a09d492e21d521ebe8858642139b600");
	}

	private static String body(String learningRate, String weightDecay) {
		return """
				{"name": "sweep", "task": "synthetic-mlp-v1", "jobs": [{"seed": 0, "config": {
				  "learningRate": %s, "hiddenUnits": 32, "hiddenLayers": 2, "batchSize": 64, "epochs": 20,
				  "optimizer": "adam", "weightDecay": %s}}]}
				""".formatted(learningRate, weightDecay);
	}

	private static CreateExperimentRequest request(String name, Integer maxAttempts, List<JobSpec> jobs) {
		return new CreateExperimentRequest(name, Task.SYNTHETIC_MLP_V1, maxAttempts, jobs);
	}

	private static JobSpec job(int seed, double learningRate) {
		SyntheticMlpConfig base = TestData.CONFIG;
		return new JobSpec(seed, new SyntheticMlpConfig(learningRate, base.hiddenUnits(), base.hiddenLayers(),
				base.batchSize(), base.epochs(), Optimizer.ADAM, base.weightDecay()));
	}

}
