package dev.codyqiu.scheduler.experiment;

/** What a submission did. The controller maps each case to an HTTP response. */
public sealed interface Submission {

	/** A new experiment and all of its jobs were created. */
	record Created(ExperimentResponse experiment) implements Submission {
	}

	/** The key was already used for an identical request; this is that original experiment. */
	record Replayed(ExperimentResponse experiment) implements Submission {
	}

	/** The key was already used for a different request. Nothing was created. */
	record KeyReused(long experimentId) implements Submission {
	}

}
