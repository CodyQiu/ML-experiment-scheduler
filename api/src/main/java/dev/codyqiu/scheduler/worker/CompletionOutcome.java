package dev.codyqiu.scheduler.worker;

/**
 * What happened to a completion report. The service returns an outcome instead of throwing, so
 * the transaction commits normally either way; the controller maps each case to an HTTP response.
 */
public sealed interface CompletionOutcome {

	/** The attempt owned the running job with a live lease; its result is now the accepted result. */
	record Accepted() implements CompletionOutcome {
	}

	/** The guarded update matched no row, so nothing changed. */
	record Rejected(Rejection rejection) implements CompletionOutcome {
	}

}
