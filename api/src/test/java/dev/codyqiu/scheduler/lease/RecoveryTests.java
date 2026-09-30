package dev.codyqiu.scheduler.lease;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import dev.codyqiu.scheduler.Concurrently;
import dev.codyqiu.scheduler.IntegrationTest;
import dev.codyqiu.scheduler.TestData;
import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobState;
import dev.codyqiu.scheduler.worker.CompletionOutcome;
import dev.codyqiu.scheduler.worker.WorkerService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryTests extends IntegrationTest {

	@Autowired
	WorkerService workers;

	@Autowired
	RecoveryService recovery;

	@Test
	void anExpiredAttemptIsEndedAndTheJobReturnsToTheQueueForAFreshAttempt() {
		long jobId = submitJobs(1).get(0);
		JobAssignment first = workers.claim("worker-a").orElseThrow();
		expireLease(jobId);

		assertThat(recovery.recoverExpiredLeases(10))
			.containsExactly(new RecoveredJob(jobId, first.attemptId(), 1, JobState.QUEUED));
		assertThat(jobRow(jobId)).isEqualTo("QUEUED attempts=1 current=null worker=null lease=null");
		assertThat(jdbc.sql("SELECT status || ' ' || error_type || ' ' || (finished_at IS NOT NULL) FROM attempts WHERE id = :id")
			.param("id", first.attemptId())
			.query(String.class)
			.single()).isEqualTo("EXPIRED LEASE_EXPIRED true");

		JobAssignment second = workers.claim("worker-b").orElseThrow();
		assertThat(second.jobId()).isEqualTo(jobId);
		assertThat(second.attemptNumber()).isEqualTo(2);
		assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
		assertThat(attemptStatus(second.attemptId())).isEqualTo("RUNNING");
	}

	@Test
	void liveLeasesFinishedJobsAndQueuedJobsAreLeftAlone() {
		List<Long> jobIds = submitJobs(3);
		JobAssignment live = workers.claim("worker-a").orElseThrow();
		JobAssignment done = workers.claim("worker-b").orElseThrow();
		assertThat(workers.complete(done.jobId(), done.attemptId(), TestData.metrics(0.9)))
			.isInstanceOf(CompletionOutcome.Accepted.class);

		assertThat(recovery.recoverExpiredLeases(10)).isEmpty();

		assertThat(jobRow(live.jobId())).startsWith("RUNNING");
		assertThat(jobRow(done.jobId())).startsWith("SUCCEEDED");
		assertThat(jobRow(jobIds.get(2))).startsWith("QUEUED");
	}

	@Test
	void whenTheFinalAttemptExpiresTheJobFailsForGood() {
		long jobId = submitJobs(1, 2).get(0);
		JobAssignment first = workers.claim("worker-a").orElseThrow();
		expireLease(jobId);
		assertThat(recovery.recoverExpiredLeases(10)).extracting(RecoveredJob::newState).containsExactly(JobState.QUEUED);
		JobAssignment second = workers.claim("worker-b").orElseThrow();
		expireLease(jobId);

		assertThat(recovery.recoverExpiredLeases(10))
			.containsExactly(new RecoveredJob(jobId, second.attemptId(), 2, JobState.FAILED));

		assertThat(jobRow(jobId)).isEqualTo("FAILED attempts=2 current=%s worker=worker-b lease=null".formatted(second.attemptId()));
		assertThat(jdbc.sql("SELECT finished_at IS NOT NULL FROM jobs WHERE id = :id").param("id", jobId).query(Boolean.class).single())
			.isTrue();
		assertThat(List.of(attemptStatus(first.attemptId()), attemptStatus(second.attemptId())))
			.containsExactly("EXPIRED", "EXPIRED");
		assertThat(workers.claim("worker-c")).isEmpty();
	}

	@RepeatedTest(3)
	void concurrentSweepsRecoverEachExpiredAttemptExactlyOnce() throws Exception {
		List<Long> jobIds = submitJobs(40);
		for (int i = 0; i < jobIds.size(); i++) {
			workers.claim("worker-" + i).orElseThrow();
		}
		jdbc.sql("UPDATE jobs SET lease_expires_at = now() - interval '1 second' WHERE state = 'RUNNING'").update();

		// Eight sweepers, as if eight API instances all ran recovery at the same moment.
		List<List<RecoveredJob>> perSweeper = Concurrently.runTogether(IntStream.range(0, 8)
			.mapToObj(sweeper -> (Callable<List<RecoveredJob>>) () -> drain(5))
			.toList());

		List<RecoveredJob> recovered = perSweeper.stream().flatMap(List::stream).toList();
		assertThat(recovered).extracting(RecoveredJob::jobId).containsExactlyInAnyOrderElementsOf(jobIds);
		assertThat(recovered).extracting(RecoveredJob::expiredAttemptId).doesNotHaveDuplicates();
		assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE state = 'QUEUED' AND attempt_count = 1").query(Integer.class).single())
			.isEqualTo(40);
		assertThat(jdbc.sql("SELECT count(*) FROM attempts WHERE status = 'EXPIRED'").query(Integer.class).single())
			.isEqualTo(40);
	}

	/** Sweeps in small batches until a sweep finds nothing, like the scheduled sweeper does. */
	private List<RecoveredJob> drain(int batchSize) {
		List<RecoveredJob> all = new ArrayList<>();
		for (List<RecoveredJob> batch = recovery.recoverExpiredLeases(batchSize); !batch.isEmpty();
				batch = recovery.recoverExpiredLeases(batchSize)) {
			all.addAll(batch);
		}
		return all;
	}

	private String jobRow(long jobId) {
		return jdbc.sql("""
				SELECT state || ' attempts=' || attempt_count || ' current=' || COALESCE(current_attempt_id::text, 'null')
				       || ' worker=' || COALESCE(worker_id, 'null') || ' lease=' || COALESCE(lease_expires_at::text, 'null')
				FROM jobs WHERE id = :id
				""").param("id", jobId).query(String.class).single();
	}

}
