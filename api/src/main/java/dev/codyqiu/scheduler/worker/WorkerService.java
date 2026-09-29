package dev.codyqiu.scheduler.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import dev.codyqiu.scheduler.job.AttemptRepository;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobRepository;
import dev.codyqiu.scheduler.lease.SchedulerProperties;
import dev.codyqiu.scheduler.task.TrainingMetrics;
import dev.codyqiu.scheduler.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerService {

	private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

	private final JobRepository jobs;

	private final AttemptRepository attempts;

	private final SchedulerProperties.Lease lease;

	public WorkerService(JobRepository jobs, AttemptRepository attempts, SchedulerProperties properties) {
		this.jobs = jobs;
		this.attempts = attempts;
		this.lease = properties.lease();
	}

	/**
	 * Claims the oldest queued job and records the new attempt, in one short transaction. The
	 * lease starts at the claim, and the transaction commits before this method returns, so no
	 * lock is held while the HTTP response travels or while the worker trains.
	 */
	@Transactional
	public Optional<JobAssignment> claim(String workerId) {
		Optional<JobAssignment> assignment = jobs.claimNext(workerId, lease);
		assignment.ifPresent(claimed -> {
			attempts.insertRunning(claimed, workerId);
			log.info("Claimed job {} attempt {} ({}) for worker {}", claimed.jobId(), claimed.attemptNumber(),
					claimed.attemptId(), workerId);
		});
		return assignment;
	}

	/** Extends the lease only if the attempt still holds the running job with a live lease. */
	@Transactional
	public HeartbeatOutcome heartbeat(long jobId, UUID attemptId) {
		Optional<Instant> renewed = jobs.renewLease(jobId, attemptId, lease);
		if (renewed.isPresent()) {
			attempts.recordHeartbeat(attemptId);
			return new HeartbeatOutcome.Renewed(renewed.get());
		}
		Rejection rejection = explainRejection(jobId, attemptId);
		log.warn("Rejected heartbeat of job {} from attempt {}: {} (job is {})", jobId, attemptId, rejection.reason(),
				rejection.jobState());
		return new HeartbeatOutcome.Rejected(rejection);
	}

	/**
	 * Accepts the result only if {@code attemptId} is the job's running attempt with a live lease.
	 * The affected-row count of one guarded UPDATE is the decision. The read that follows a
	 * rejection only explains it: it can neither undo the rejection nor lead to a write.
	 */
	@Transactional
	public CompletionOutcome complete(long jobId, UUID attemptId, TrainingMetrics metrics) {
		if (jobs.markSucceeded(jobId, attemptId, metrics)) {
			attempts.markSucceeded(attemptId);
			log.info("Accepted result of job {} from attempt {} (valAccuracy={})", jobId, attemptId,
					metrics.valAccuracy());
			return new CompletionOutcome.Accepted();
		}
		Rejection rejection = explainRejection(jobId, attemptId);
		log.warn("Rejected completion of job {} from attempt {}: {} (job is {})", jobId, attemptId,
				rejection.reason(), rejection.jobState());
		return new CompletionOutcome.Rejected(rejection);
	}

	private Rejection explainRejection(long jobId, UUID attemptId) {
		return Rejection.explain(jobs.findStanding(jobId, attemptId)
			.orElseThrow(() -> new NotFoundException("Job", jobId)));
	}

}
