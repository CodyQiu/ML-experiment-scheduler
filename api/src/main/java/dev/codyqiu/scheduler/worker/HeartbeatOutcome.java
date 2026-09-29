package dev.codyqiu.scheduler.worker;

import java.time.Instant;

public sealed interface HeartbeatOutcome {

	/** The lease was extended; {@code leaseExpiresAt} is database time. */
	record Renewed(Instant leaseExpiresAt) implements HeartbeatOutcome {
	}

	/** The guarded update matched no row: the attempt has lost its authority, so it must stop. */
	record Rejected(Rejection rejection) implements HeartbeatOutcome {
	}

}
