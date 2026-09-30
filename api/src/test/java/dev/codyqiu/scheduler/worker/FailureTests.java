package dev.codyqiu.scheduler.worker;

import java.time.Instant;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.AttemptRepository;
import dev.codyqiu.scheduler.job.AttemptResponse;
import dev.codyqiu.scheduler.job.AttemptStatus;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobState;
import dev.codyqiu.scheduler.lease.RecoveredJob;
import dev.codyqiu.scheduler.lease.RecoveryService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Reported failures and the attempt budget. maxAttempts counts every execution, however it ends:
 * a reported failure, an expired lease, or a success.
 */
class FailureTests extends IntegrationTest {

	@Autowired
	WorkerService workers;

	@Autowired
	RecoveryService recovery;

	@Autowired
	AttemptRepository attempts;

	@Test
	void aRetryableFailureWithAttemptsLeftQueuesAFreshAttempt() {
		long jobId = submitJobs(1).get(0);
		JobAssignment first = workers.claim("worker-a").orElseThrow();

		assertThat(workers.fail(jobId, first.attemptId(), true, "WORKER_ERROR", "RuntimeError: boom"))
			.isEqualTo(new FailureOutcome.Recorded(JobState.QUEUED));

		JobAssignment second = workers.claim("worker-b").orElseThrow();
		assertThat(second.jobId()).isEqualTo(jobId);
		assertThat(second.attemptNumber()).isEqualTo(2);
		assertThat(attempts.findByJobId(jobId).get(0)).satisfies(attempt -> {
			assertThat(attempt.status()).isEqualTo(AttemptStatus.FAILED);
			assertThat(attempt.errorType()).isEqualTo("WORKER_ERROR");
			assertThat(attempt.errorMessage()).isEqualTo("RuntimeError: boom");
			assertThat(attempt.retryable()).isTrue();
			assertThat(attempt.finishedAt()).isNotNull();
		});
	}

	@Test
	void aNonRetryableFailureEndsTheJobAtOnceEvenWithAttemptsLeft() {
		long jobId = submitJobs(1, 3).get(0);
		JobAssignment attempt = workers.claim("worker-a").orElseThrow();

		assertThat(workers.fail(jobId, attempt.attemptId(), false, "TRAINING_DIVERGED", "validation loss is nan"))
			.isEqualTo(new FailureOutcome.Recorded(JobState.FAILED));

		assertThat(jdbc.sql("SELECT state || ' ' || attempt_count || ' ' || (finished_at IS NOT NULL) FROM jobs WHERE id = :id")
			.param("id", jobId)
			.query(String.class)
			.single()).isEqualTo("FAILED 1 true");
		assertThat(workers.claim("worker-b")).isEmpty();
	}

	@Test
	void retriesStopAtMaxAttemptsThroughReportedFailures() {
		long jobId = submitJobs(1, 3).get(0);
		for (int attempt = 1; attempt <= 3; attempt++) {
			JobAssignment claimed = workers.claim("worker-" + attempt).orElseThrow();
			JobState expected = (attempt < 3) ? JobState.QUEUED : JobState.FAILED;
			assertThat(workers.fail(jobId, claimed.attemptId(), true, "WORKER_ERROR", "attempt " + attempt))
				.isEqualTo(new FailureOutcome.Recorded(expected));
		}

		assertThat(workers.claim("worker-4")).isEmpty();
		assertThat(attempts.findByJobId(jobId)).extracting(AttemptResponse::status)
			.containsExactly(AttemptStatus.FAILED, AttemptStatus.FAILED, AttemptStatus.FAILED);
	}

	@Test
	void theBudgetCountsExpiriesAndReportedFailuresAlike() {
		long jobId = submitJobs(1, 3).get(0);
		workers.claim("worker-a").orElseThrow();
		expireLease(jobId);
		recovery.recoverExpiredLeases(10);
		JobAssignment second = workers.claim("worker-b").orElseThrow();
		assertThat(workers.fail(jobId, second.attemptId(), true, "WORKER_SHUTDOWN", "stopped mid-run"))
			.isEqualTo(new FailureOutcome.Recorded(JobState.QUEUED));
		workers.claim("worker-c").orElseThrow();
		expireLease(jobId);

		assertThat(recovery.recoverExpiredLeases(10)).extracting(RecoveredJob::newState)
			.containsExactly(JobState.FAILED);
		assertThat(workers.claim("worker-d")).isEmpty();
		assertThat(attempts.findByJobId(jobId)).extracting(AttemptResponse::status, AttemptResponse::errorType)
			.containsExactly(tuple(AttemptStatus.EXPIRED, "LEASE_EXPIRED"),
					tuple(AttemptStatus.FAILED, "WORKER_SHUTDOWN"),
					tuple(AttemptStatus.EXPIRED, "LEASE_EXPIRED"));
	}

	@Test
	void aStaleAttemptCannotFailTheAttemptThatReplacedIt() {
		long jobId = submitJobs(1).get(0);
		JobAssignment stale = workers.claim("worker-a").orElseThrow();
		expireLease(jobId);
		recovery.recoverExpiredLeases(10);
		JobAssignment current = workers.claim("worker-b").orElseThrow();
		Instant leaseBefore = storedLease(jobId);

		assertThat(workers.fail(jobId, stale.attemptId(), false, "TRAINING_DIVERGED", "late report"))
			.isEqualTo(new FailureOutcome.Rejected(new Rejection(Rejection.Reason.ATTEMPT_NOT_CURRENT, JobState.RUNNING)));

		assertThat(storedLease(jobId)).isEqualTo(leaseBefore);
		assertThat(attemptStatus(current.attemptId())).isEqualTo("RUNNING");
		assertThat(workers.complete(jobId, current.attemptId(), TestData.metrics(0.9)))
			.isEqualTo(new CompletionOutcome.Accepted());
	}

	@Test
	void anExpiredLeaseCannotReportAFailureEvenBeforeRecoveryRuns() {
		long jobId = submitJobs(1).get(0);
		JobAssignment attempt = workers.claim("worker-a").orElseThrow();
		expireLease(jobId);

		assertThat(workers.fail(jobId, attempt.attemptId(), true, "WORKER_ERROR", "too late"))
			.isEqualTo(new FailureOutcome.Rejected(new Rejection(Rejection.Reason.LEASE_EXPIRED, JobState.RUNNING)));
		// Recovery then ends the attempt as EXPIRED; the late failure left no trace.
		assertThat(recovery.recoverExpiredLeases(10)).hasSize(1);
		assertThat(attemptStatus(attempt.attemptId())).isEqualTo("EXPIRED");
	}

	@Test
	void aFailureReportCannotUndoAnAcceptedResult() {
		long jobId = submitJobs(1).get(0);
		JobAssignment attempt = workers.claim("worker-a").orElseThrow();
		workers.complete(jobId, attempt.attemptId(), TestData.metrics(0.9));

		assertThat(workers.fail(jobId, attempt.attemptId(), false, "TRAINING_DIVERGED", "second thoughts"))
			.isEqualTo(new FailureOutcome.Rejected(
					new Rejection(Rejection.Reason.ATTEMPT_NOT_CURRENT, JobState.SUCCEEDED)));
		assertThat(attemptStatus(attempt.attemptId())).isEqualTo("SUCCEEDED");
	}

}
