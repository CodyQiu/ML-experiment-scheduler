package dev.codyqiu.scheduler.experiment;

import dev.codyqiu.scheduler.job.JobRepository;
import dev.codyqiu.scheduler.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentService {

	private final ExperimentRepository experiments;

	private final JobRepository jobs;

	public ExperimentService(ExperimentRepository experiments, JobRepository jobs) {
		this.experiments = experiments;
		this.jobs = jobs;
	}

	/**
	 * Creates the experiment and all of its jobs in one short transaction. If any row fails to
	 * insert, everything rolls back, so no reader or worker ever sees a half-created batch.
	 * Request validation has already finished before this method runs.
	 */
	@Transactional
	public ExperimentResponse create(CreateExperimentRequest request) {
		int maxAttempts = request.effectiveMaxAttempts();
		long experimentId = experiments.insert(request.name(), request.task(), maxAttempts);
		jobs.insertAll(experimentId, maxAttempts, request.jobs());
		return experiments.findWithProgress(experimentId).orElseThrow();
	}

	public ExperimentResponse get(long id) {
		return experiments.findWithProgress(id).orElseThrow(() -> new NotFoundException("Experiment", id));
	}

	public ExperimentJobsResponse listJobs(long id) {
		if (!experiments.exists(id)) {
			throw new NotFoundException("Experiment", id);
		}
		return new ExperimentJobsResponse(id, jobs.findByExperimentId(id));
	}

}
