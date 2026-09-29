package dev.codyqiu.scheduler.experiment;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/experiments")
public class ExperimentController {

	private final ExperimentService experiments;

	public ExperimentController(ExperimentService experiments) {
		this.experiments = experiments;
	}

	@PostMapping
	public ResponseEntity<ExperimentResponse> create(@Valid @RequestBody CreateExperimentRequest request) {
		ExperimentResponse created = experiments.create(request);
		return ResponseEntity.created(URI.create("/experiments/" + created.id())).body(created);
	}

	@GetMapping("/{id}")
	public ExperimentResponse get(@PathVariable long id) {
		return experiments.get(id);
	}

	@GetMapping("/{id}/jobs")
	public ExperimentJobsResponse jobs(@PathVariable long id) {
		return experiments.listJobs(id);
	}

}
