package dev.codyqiu.scheduler.worker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import dev.codyqiu.scheduler.job.JobAssignment;
import dev.codyqiu.scheduler.job.JobState;
import dev.codyqiu.scheduler.web.ConflictException;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/worker/jobs")
public class WorkerController {

	private final WorkerService workers;

	public WorkerController(WorkerService workers) {
		this.workers = workers;
	}

	/** {@code 200} with an assignment, or {@code 204 No Content} when no queued job is available. */
	@PostMapping("/claim")
	public ResponseEntity<JobAssignment> claim(@Valid @RequestBody ClaimRequest request) {
		return workers.claim(request.workerId())
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** {@code 200} with the new lease expiry; {@code 409} if the attempt has lost its authority. */
	@PostMapping("/{jobId}/heartbeat")
	public HeartbeatResponse heartbeat(@PathVariable long jobId, @Valid @RequestBody HeartbeatRequest request) {
		return switch (workers.heartbeat(jobId, request.attemptId())) {
			case HeartbeatOutcome.Renewed(Instant leaseExpiresAt) -> new HeartbeatResponse(jobId, leaseExpiresAt);
			case HeartbeatOutcome.Rejected(Rejection rejection) -> throw conflict(jobId, request.attemptId(), rejection);
		};
	}

	/** {@code 200} if the result was accepted; {@code 409} if the attempt has lost its authority. */
	@PostMapping("/{jobId}/complete")
	public CompletionResponse complete(@PathVariable long jobId, @Valid @RequestBody CompleteJobRequest request) {
		return switch (workers.complete(jobId, request.attemptId(), request.metrics())) {
			case CompletionOutcome.Accepted() -> new CompletionResponse(jobId, JobState.SUCCEEDED, false);
			case CompletionOutcome.Replayed() -> new CompletionResponse(jobId, JobState.SUCCEEDED, true);
			case CompletionOutcome.Rejected(Rejection rejection) -> throw conflict(jobId, request.attemptId(), rejection);
		};
	}

	/** {@code 200} with the job's new state; {@code 409} if the attempt has lost its authority. */
	@PostMapping("/{jobId}/fail")
	public FailureResponse fail(@PathVariable long jobId, @Valid @RequestBody FailJobRequest request) {
		return switch (workers.fail(jobId, request.attemptId(), request.retryable(), request.errorType(),
				request.message())) {
			case FailureOutcome.Recorded(JobState jobState) -> new FailureResponse(jobId, jobState);
			case FailureOutcome.Rejected(Rejection rejection) -> throw conflict(jobId, request.attemptId(), rejection);
		};
	}

	private static ConflictException conflict(long jobId, UUID attemptId, Rejection rejection) {
		String detail = switch (rejection.reason()) {
			case LEASE_EXPIRED -> "The lease of attempt %s on job %d has expired".formatted(attemptId, jobId);
			case ATTEMPT_NOT_CURRENT -> "Attempt %s is not the running attempt of job %d".formatted(attemptId, jobId);
			case RESULT_CONFLICT -> "Attempt %s already reported a different result for job %d".formatted(attemptId,
					jobId);
		};
		return new ConflictException(rejection.reason().name(), detail,
				Map.of("jobId", jobId, "jobState", rejection.jobState()));
	}

}
