package dev.codyqiu.scheduler.worker;

/**
 * What happened to a completion report. The service returns an outcome instead of throwing, so
 * the transaction commits normally either way; the controller maps each case to an HTTP response.
 */
public sealed interface CompletionOutcome {

	/** The attempt owned the running job with a live lease; its result is now the accepted result. */
	record Accepted() implements CompletionOutcome {
	}

	/**
	 * A repeat of the report that was already accepted, e.g. a retry after a lost response. Nothing
	 * changed; the worker can treat it exactly like {@link Accepted}.
	 */
	record Replayed() implements CompletionOutcome {
	}

	/** The guarded update matched no row, so nothing changed. */
	record Rejected(Rejection rejection) implements CompletionOutcome {
	}

}
