package dev.codyqiu.scheduler.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.codyqiu.scheduler.Concurrently;
import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobState;
import dev.codyqiu.scheduler.task.TrainingMetrics;
import org.junit.jupiter.api.RepeatedTest;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races real transactions against PostgreSQL. Each thread holds its own pooled connection (8
 * threads; Hikari's default pool has 10), and a start gate releases the threads together so they
 * contend for the same rows. Each test repeats because one run may not hit every interleaving.
 */
class WorkerConcurrencyTests extends IntegrationTest {

	private static final int THREADS = 8;

	@Autowired
	WorkerService workers;

	@RepeatedTest(5)
	void workersDrainingTheQueueTogetherClaimEveryJobExactlyOnce() throws Exception {
		List<Long> jobIds = submitJobs(100);

		List<List<JobAssignment>> perWorker = Concurrently.runTogether(IntStream.range(0, THREADS)
			.mapToObj(worker -> (Callable<List<JobAssignment>>) () -> drainQueue("worker-" + worker))
			.toList());

		List<JobAssignment> claims = perWorker.stream().flatMap(List::stream).toList();
		// Every job was handed out exactly once, each time under a distinct attempt id...
		assertThat(claims).extracting(JobAssignment::jobId).containsExactlyInAnyOrderElementsOf(jobIds);
		assertThat(claims).extracting(JobAssignment::attemptId).doesNotHaveDuplicates();
		assertThat(claims).extracting(JobAssignment::attemptNumber).containsOnly(1);
		// ...and the database agrees with what each worker was told.
		Map<Long, UUID> stored = runningAttempts();
		assertThat(stored).hasSize(jobIds.size());
		claims.forEach(claim -> assertThat(stored).containsEntry(claim.jobId(), claim.attemptId()));
		// Each claim recorded exactly one live attempt row, keyed by the attempt id it handed out.
		assertThat(jdbc.sql("SELECT id FROM attempts WHERE status = 'RUNNING'").query(UUID.class).list())
			.containsExactlyInAnyOrderElementsOf(claims.stream().map(JobAssignment::attemptId).toList());
	}

	@RepeatedTest(5)
	void simultaneousClaimsForFewerJobsThanWorkersNeverDoubleAssign() throws Exception {
		List<Long> jobIds = submitJobs(3);

		List<Optional<JobAssignment>> results = Concurrently.runTogether(IntStream.range(0, THREADS)
			.mapToObj(worker -> (Callable<Optional<JobAssignment>>) () -> workers.claim("worker-" + worker))
			.toList());

		List<JobAssignment> claims = results.stream().flatMap(Optional::stream).toList();
		assertThat(claims).extracting(JobAssignment::jobId).containsExactlyInAnyOrderElementsOf(jobIds);
		assertThat(results).filteredOn(Optional::isEmpty).hasSize(THREADS - jobIds.size());
	}

	@RepeatedTest(5)
	void simultaneousCompletionsOfOneAttemptAcceptExactlyOneResult() throws Exception {
		submitJobs(1);
		JobAssignment assignment = workers.claim("worker-1").orElseThrow();
		// Duplicate deliveries of one attempt's report, each with a distinguishable payload.
		List<TrainingMetrics> reports = IntStream.range(0, THREADS)
			.mapToObj(i -> TestData.metrics(0.50 + i / 100.0))
			.toList();

		List<CompletionOutcome> outcomes = Concurrently.runTogether(reports.stream()
			.map(report -> (Callable<CompletionOutcome>) () -> workers.complete(assignment.jobId(),
					assignment.attemptId(), report))
			.toList());

		List<Integer> winners = IntStream.range(0, THREADS)
			.filter(i -> outcomes.get(i) instanceof CompletionOutcome.Accepted)
			.boxed()
			.toList();
		assertThat(winners).hasSize(1);
		// Each loser waited for the winner's row lock, then found the attempt's result already
		// accepted, and different from its own.
		assertThat(outcomes).filteredOn(CompletionOutcome.Rejected.class::isInstance)
			.hasSize(THREADS - 1)
			.containsOnly(new CompletionOutcome.Rejected(
					new Rejection(Rejection.Reason.RESULT_CONFLICT, JobState.SUCCEEDED)));
		double stored = jdbc.sql("SELECT val_accuracy FROM jobs WHERE id = :id")
			.param("id", assignment.jobId())
			.query(Double.class)
			.single();
		assertThat(stored).isEqualTo(reports.get(winners.get(0)).valAccuracy());
	}

	@RepeatedTest(5)
	void simultaneousDeliveriesOfTheSameReportAcceptOneAndReplayTheRest() throws Exception {
		submitJobs(1);
		JobAssignment assignment = workers.claim("worker-1").orElseThrow();

		List<CompletionOutcome> outcomes = Concurrently.runTogether(IntStream.range(0, THREADS)
			.mapToObj(i -> (Callable<CompletionOutcome>) () -> workers.complete(assignment.jobId(),
					assignment.attemptId(), TestData.metrics(0.9)))
			.toList());

		assertThat(outcomes).filteredOn(CompletionOutcome.Accepted.class::isInstance).hasSize(1);
		assertThat(outcomes).filteredOn(CompletionOutcome.Replayed.class::isInstance).hasSize(THREADS - 1);
		assertThat(countRows("attempts")).isOne();
	}

	private List<JobAssignment> drainQueue(String workerId) {
		List<JobAssignment> claimed = new ArrayList<>();
		for (Optional<JobAssignment> next = workers.claim(workerId); next.isPresent(); next = workers.claim(workerId)) {
			claimed.add(next.get());
		}
		return claimed;
	}

	private Map<Long, UUID> runningAttempts() {
		return jdbc.sql("SELECT id, current_attempt_id FROM jobs WHERE state = 'RUNNING' AND attempt_count = 1")
			.query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getObject("current_attempt_id", UUID.class)))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}
