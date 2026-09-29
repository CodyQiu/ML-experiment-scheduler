package dev.codyqiu.scheduler.lease;

import java.util.List;

import dev.codyqiu.scheduler.job.AttemptRepository;
import dev.codyqiu.scheduler.job.ExpiredLease;
import dev.codyqiu.scheduler.job.JobRepository;
import dev.codyqiu.scheduler.job.JobState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryService {

	private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

	private final JobRepository jobs;

	private final AttemptRepository attempts;

	public RecoveryService(JobRepository jobs, AttemptRepository attempts) {
		this.jobs = jobs;
		this.attempts = attempts;
	}

	/**
	 * Ends up to {@code limit} attempts whose lease has expired, in one short transaction. A job
	 * with attempts left returns to the queue for a fresh attempt; a job whose final attempt
	 * expired becomes FAILED.
	 *
	 * <p>Safe to run concurrently, e.g. on several API instances. Each sweep locks the rows it
	 * recovers and skips rows that others hold. A row that changed before the lock (renewed,
	 * completed, or already recovered) no longer matches the re-checked expiry predicate, so every
	 * expired attempt is ended exactly once.
	 */
	@Transactional
	public List<RecoveredJob> recoverExpiredLeases(int limit) {
		List<ExpiredLease> expired = jobs.lockExpiredLeases(limit);
		if (expired.isEmpty()) {
			return List.of();
		}
		List<Long> jobIds = expired.stream().map(ExpiredLease::jobId).toList();
		int requeued = jobs.requeue(jobIds);
		int failed = jobs.failExhausted(jobIds);
		if (requeued + failed != expired.size()) {
			throw new IllegalStateException("Recovered %d of %d locked jobs".formatted(requeued + failed, expired.size()));
		}
		attempts.markExpired(expired.stream().map(ExpiredLease::attemptId).toList());

		List<RecoveredJob> recovered = expired.stream()
			.map(lease -> new RecoveredJob(lease.jobId(), lease.attemptId(), lease.attemptNumber(),
					lease.hasAttemptsLeft() ? JobState.QUEUED : JobState.FAILED))
			.toList();
		for (int i = 0; i < expired.size(); i++) {
			ExpiredLease lease = expired.get(i);
			log.warn("Lease of job {} attempt {} ({}, worker {}) expired: job is now {} ({} of {} attempts used)",
					lease.jobId(), lease.attemptNumber(), lease.attemptId(), lease.workerId(),
					recovered.get(i).newState(), lease.attemptNumber(), lease.maxAttempts());
		}
		return recovered;
	}

}
