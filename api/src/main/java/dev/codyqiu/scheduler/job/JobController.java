package dev.codyqiu.scheduler.job;

import dev.codyqiu.scheduler.web.NotFoundException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {

	private final JobRepository jobs;

	private final AttemptRepository attempts;

	public JobController(JobRepository jobs, AttemptRepository attempts) {
		this.jobs = jobs;
		this.attempts = attempts;
	}

	@GetMapping("/jobs/{id}")
	public JobResponse get(@PathVariable long id) {
		return jobs.findById(id).orElseThrow(() -> new NotFoundException("Job", id));
	}

	/** Every execution of the job in order: who ran it, how it ended, and why. */
	@GetMapping("/jobs/{id}/attempts")
	public JobAttemptsResponse attempts(@PathVariable long id) {
		if (!jobs.exists(id)) {
			throw new NotFoundException("Job", id);
		}
		return new JobAttemptsResponse(id, attempts.findByJobId(id));
	}

}
