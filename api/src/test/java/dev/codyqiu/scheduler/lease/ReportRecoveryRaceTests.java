package dev.codyqiu.scheduler.lease;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobState;
import dev.codyqiu.scheduler.worker.CompletionOutcome;
import dev.codyqiu.scheduler.worker.FailureOutcome;
import dev.codyqiu.scheduler.worker.Rejection;
import dev.codyqiu.scheduler.worker.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The race between a worker's report (a completion, or a non-retryable failure) and lease-expiry
 * recovery. Both orders are forced deterministically.
 *
 * <p>Each side runs in its own transaction on its own thread, and PostgreSQL fixes a
 * transaction's {@code now()} when it starts. The report's transaction starts first; then the
 * lease is placed strictly between the two start times. The report therefore sees a live lease and
 * recovery sees an expired one: both guards pass, each in its own transaction, and only the row
 * lock decides who wins. Whichever side locks the row first must be the only one whose write
 * lands.
 */
class ReportRecoveryRaceTests extends IntegrationTest {

	/** What the attempt reports, and the job and attempt states that report produces when it wins. */
	enum Report {

		COMPLETE("SUCCEEDED"), FAIL("FAILED");

		final String winningState;

		Report(String winningState) {
			this.winningState = winningState;
		}

	}

	@Autowired
	WorkerService workers;

	@Autowired
	RecoveryService recovery;

	@Autowired
	PlatformTransactionManager transactionManager;

	private long jobId;

	private JobAssignment attempt;

	@BeforeEach
	void claimOneJob() {
		jobId = submitJobs(1).get(0);
		attempt = workers.claim("worker-a").orElseThrow();
	}

	@ParameterizedTest
	@EnumSource(Report.class)
	void whenRecoveryLocksTheRowFirstTheReportWaitsAndIsThenRejected(Report report) throws Exception {
		CountDownLatch leasePlaced = new CountDownLatch(1);
		CountDownLatch recoveryHoldsRow = new CountDownLatch(1);
		CountDownLatch releaseRecovery = new CountDownLatch(1);

		HeldTransaction<Object> reporting = begin(() -> {
			await(recoveryHoldsRow);
			return send(report);
		});
		HeldTransaction<List<RecoveredJob>> recovering = begin(() -> {
			await(leasePlaced);
			List<RecoveredJob> recovered = recovery.recoverExpiredLeases(10);
			recoveryHoldsRow.countDown();
			await(releaseRecovery);
			return recovered;
		});
		placeLeaseBetween(reporting.startedAt(), recovering.startedAt());
		leasePlaced.countDown();
		awaitTransactionBlockedOnLock(); // the report's UPDATE is waiting for recovery's row lock
		releaseRecovery.countDown();

		assertThat(recovering.result()).containsExactly(new RecoveredJob(jobId, attempt.attemptId(), 1, JobState.QUEUED));
		// After recovery committed, the report re-checked its WHERE clause against the new row
		// (QUEUED, no current attempt) and updated nothing.
		assertThat(rejectionOf(reporting.result()))
			.contains(new Rejection(Rejection.Reason.ATTEMPT_NOT_CURRENT, JobState.QUEUED));
		assertThat(jobState()).isEqualTo("QUEUED");
		assertThat(attemptStatus(attempt.attemptId())).isEqualTo("EXPIRED");
	}

	@ParameterizedTest
	@EnumSource(Report.class)
	void whenTheReportLocksTheRowFirstRecoverySkipsItAndTheReportStands(Report report) throws Exception {
		CountDownLatch leasePlaced = new CountDownLatch(1);
		CountDownLatch reportHoldsRow = new CountDownLatch(1);
		CountDownLatch releaseReport = new CountDownLatch(1);

		HeldTransaction<Object> reporting = begin(() -> {
			await(leasePlaced);
			Object outcome = send(report);
			reportHoldsRow.countDown();
			await(releaseReport);
			return outcome;
		});
		HeldTransaction<List<RecoveredJob>> recovering = begin(() -> {
			await(reportHoldsRow);
			return recovery.recoverExpiredLeases(10);
		});
		placeLeaseBetween(reporting.startedAt(), recovering.startedAt());
		leasePlaced.countDown();

		// Recovery saw the row as expired but found it locked. SKIP LOCKED passed over it, so it
		// finished without waiting, while the report still held the row.
		assertThat(recovering.result()).isEmpty();
		releaseReport.countDown();

		assertThat(rejectionOf(reporting.result())).isEmpty();
		assertThat(jobState()).isEqualTo(report.winningState);
		assertThat(attemptStatus(attempt.attemptId())).isEqualTo(report.winningState);
		assertThat(recovery.recoverExpiredLeases(10)).isEmpty();
	}

	private Object send(Report report) {
		return switch (report) {
			case COMPLETE -> workers.complete(jobId, attempt.attemptId(), TestData.metrics(0.9));
			case FAIL -> workers.fail(jobId, attempt.attemptId(), false, "TRAINING_DIVERGED", "validation loss is nan");
		};
	}

	private static Optional<Rejection> rejectionOf(Object outcome) {
		return switch (outcome) {
			case CompletionOutcome.Rejected(Rejection rejection) -> Optional.of(rejection);
			case FailureOutcome.Rejected(Rejection rejection) -> Optional.of(rejection);
			default -> Optional.empty();
		};
	}

	private String jobState() {
		return jdbc.sql("SELECT state FROM jobs WHERE id = :id").param("id", jobId).query(String.class).single();
	}

	/** Sets the job's lease strictly after the report's start and before recovery's. */
	private void placeLeaseBetween(OffsetDateTime reportStarted, OffsetDateTime recoveryStarted) {
		assertThat(recoveryStarted).isAfter(reportStarted);
		Duration gap = Duration.between(reportStarted, recoveryStarted);
		jdbc.sql("UPDATE jobs SET lease_expires_at = :lease WHERE id = :id")
			.param("lease", reportStarted.plus(gap.dividedBy(2)))
			.param("id", jobId)
			.update();
	}

	/** Waits until PostgreSQL reports a session blocked on a lock (not a sleep-and-hope). */
	private void awaitTransactionBlockedOnLock() throws InterruptedException {
		for (int i = 0; i < 400; i++) {
			int waiting = jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					""").query(Integer.class).single();
			if (waiting > 0) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("no transaction ever blocked on the row lock");
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		if (!latch.await(10, TimeUnit.SECONDS)) {
			throw new AssertionError("timed out waiting for the other transaction");
		}
	}

	private <T> HeldTransaction<T> begin(Callable<T> body) throws Exception {
		return HeldTransaction.begin(new TransactionTemplate(transactionManager), this, body);
	}

	/**
	 * A transaction on its own thread. {@link #begin} returns once the transaction has run its
	 * first statement, which fixes its {@code now()}; the body then runs inside that transaction,
	 * and service methods called there join it rather than starting their own.
	 */
	private record HeldTransaction<T>(OffsetDateTime startedAt, Future<T> outcome) {

		static <T> HeldTransaction<T> begin(TransactionTemplate template, ReportRecoveryRaceTests test,
				Callable<T> body) throws Exception {
			ExecutorService thread = Executors.newSingleThreadExecutor();
			CompletableFuture<OffsetDateTime> started = new CompletableFuture<>();
			Future<T> outcome = thread.submit(() -> template.execute(status -> {
				started.complete(test.jdbc.sql("SELECT now()").query(OffsetDateTime.class).single());
				try {
					return body.call();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}));
			thread.shutdown();
			return new HeldTransaction<>(started.get(10, TimeUnit.SECONDS), outcome);
		}

		T result() throws Exception {
			return outcome.get(10, TimeUnit.SECONDS);
		}

	}

}
