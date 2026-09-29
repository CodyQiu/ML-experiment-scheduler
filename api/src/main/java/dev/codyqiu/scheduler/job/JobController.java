package dev.codyqiu.scheduler.job;

import dev.codyqiu.scheduler.web.NotFoundException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {

	private final JobRepository jobs;

	public JobController(JobRepository jobs) {
		this.jobs = jobs;
	}

	@GetMapping("/jobs/{id}")
	public JobResponse get(@PathVariable long id) {
		return jobs.findById(id).orElseThrow(() -> new NotFoundException("Job", id));
	}

}
