package dev.codyqiu.scheduler.experiment;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

import dev.codyqiu.scheduler.web.ConflictException;
import dev.codyqiu.scheduler.web.RequestValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/experiments")
public class ExperimentController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

	private final ExperimentService experiments;

	public ExperimentController(ExperimentService experiments) {
		this.experiments = experiments;
	}

	/**
	 * {@code 201} for a new experiment. With an {@code Idempotency-Key} that was already used:
	 * {@code 200} with the original experiment (header {@code Idempotent-Replayed: true}) for an
	 * identical request, or {@code 409 IDEMPOTENCY_KEY_REUSED} for a different one.
	 */
	@PostMapping
	public ResponseEntity<ExperimentResponse> create(@Valid @RequestBody CreateExperimentRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
		if (idempotencyKey != null && !VALID_KEY.matcher(idempotencyKey).matches()) {
			throw new RequestValidationException(IDEMPOTENCY_KEY, "must be 1-255 visible ASCII characters");
		}
		return switch (experiments.submit(request, idempotencyKey)) {
			case Submission.Created(ExperimentResponse created) ->
				ResponseEntity.created(URI.create("/experiments/" + created.id())).body(created);
			case Submission.Replayed(ExperimentResponse original) ->
				ResponseEntity.ok().header("Idempotent-Replayed", "true").body(original);
			case Submission.KeyReused(long experimentId) -> throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
					"Idempotency-Key was already used for a different request (experiment %d)".formatted(experimentId),
					Map.of("experimentId", experimentId));
		};
	}

	@GetMapping("/{id}")
	public ExperimentResponse get(@PathVariable long id) {
		return experiments.get(id);
	}

	/** Successful jobs ranked by {@code valAccuracy}, best first; {@code limit} is 1–100 (default 10). */
	@GetMapping("/{id}/best")
	public BestJobsResponse best(@PathVariable long id,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
		return experiments.best(id, limit);
	}

	@GetMapping("/{id}/jobs")
	public ExperimentJobsResponse jobs(@PathVariable long id) {
		return experiments.listJobs(id);
	}

}
