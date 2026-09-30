package dev.codyqiu.scheduler.experiment;

import java.util.Optional;

import dev.codyqiu.scheduler.job.JobRepository;
import dev.codyqiu.scheduler.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentService {

	private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

	private final ExperimentRepository experiments;

	private final JobRepository jobs;

	public ExperimentService(ExperimentRepository experiments, JobRepository jobs) {
		this.experiments = experiments;
		this.jobs = jobs;
	}

	/**
	 * Creates the experiment without an idempotency key.
	 *
	 * <p>{@code @Transactional} is needed here too. {@code submit(...)} below is a call on
	 * {@code this}, which bypasses the Spring proxy, so {@code submit}'s own annotation does not
	 * apply to it. Without this one, the repositories' {@code MANDATORY} propagation would refuse
	 * to write outside a transaction (it did, which is how this was caught).
	 */
	@Transactional
	public ExperimentResponse create(CreateExperimentRequest request) {
		return switch (submit(request, null)) {
			case Submission.Created(ExperimentResponse created) -> created;
			case Submission.Replayed replayed -> throw new IllegalStateException("no key, so nothing to replay");
			case Submission.KeyReused reused -> throw new IllegalStateException("no key, so nothing to reuse");
		};
	}

	/**
	 * Creates the experiment and all of its jobs in one short transaction. If any row fails to
	 * insert, everything rolls back, so no reader or worker ever sees a half-created batch.
	 * Request validation has already finished before this method runs.
	 *
	 * <p>With an {@code idempotencyKey}, the key and the request's fingerprint are stored with the
	 * experiment. If the key is already taken, nothing is created. The original experiment is
	 * returned when the fingerprints match, and a conflict is reported when they differ. The
	 * unique constraint makes this hold even for concurrent requests with the same key.
	 */
	@Transactional
	public Submission submit(CreateExperimentRequest request, String idempotencyKey) {
		int maxAttempts = request.effectiveMaxAttempts();
		String fingerprint = (idempotencyKey != null) ? RequestFingerprint.of(request) : null;
		Optional<Long> inserted = experiments.insert(request.name(), request.task(), maxAttempts, idempotencyKey,
				fingerprint);
		if (inserted.isPresent()) {
			jobs.insertAll(inserted.get(), maxAttempts, request.jobs());
			LoggingEventBuilder created = log.atInfo()
				.addKeyValue("event.action", "experiment.created")
				.addKeyValue("experimentId", inserted.get())
				.addKeyValue("jobCount", request.jobs().size())
				.addKeyValue("maxAttempts", maxAttempts);
			if (idempotencyKey != null) {
				created.addKeyValue("idempotencyKey", idempotencyKey);
			}
			created.log("Created experiment {} with {} jobs", inserted.get(), request.jobs().size());
			return new Submission.Created(experiments.findWithProgress(inserted.get()).orElseThrow());
		}
		StoredKey stored = experiments.findByIdempotencyKey(idempotencyKey).orElseThrow();
		if (!stored.fingerprint().equals(fingerprint)) {
			log.atWarn()
				.addKeyValue("event.action", "submission.key_reused")
				.addKeyValue("experimentId", stored.experimentId())
				.addKeyValue("idempotencyKey", idempotencyKey)
				.log("Idempotency-Key {} reused for a different request (it belongs to experiment {})",
						idempotencyKey, stored.experimentId());
			return new Submission.KeyReused(stored.experimentId());
		}
		log.atInfo()
			.addKeyValue("event.action", "submission.replayed")
			.addKeyValue("experimentId", stored.experimentId())
			.addKeyValue("idempotencyKey", idempotencyKey)
			.log("Idempotency-Key {} replayed: returning experiment {} without creating anything", idempotencyKey,
					stored.experimentId());
		return new Submission.Replayed(experiments.findWithProgress(stored.experimentId()).orElseThrow());
	}

	public ExperimentListResponse listRecent(int limit) {
		return new ExperimentListResponse(experiments.findRecentWithProgress(limit));
	}

	public ExperimentResponse get(long id) {
		return experiments.findWithProgress(id).orElseThrow(() -> new NotFoundException("Experiment", id));
	}

	public BestJobsResponse best(long id, int limit) {
		if (!experiments.exists(id)) {
			throw new NotFoundException("Experiment", id);
		}
		return new BestJobsResponse(id, "valAccuracy", jobs.findBest(id, limit));
	}

	public ExperimentJobsResponse listJobs(long id) {
		if (!experiments.exists(id)) {
			throw new NotFoundException("Experiment", id);
		}
		return new ExperimentJobsResponse(id, jobs.findByExperimentId(id));
	}

}
