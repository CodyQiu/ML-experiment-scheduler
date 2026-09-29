package dev.codyqiu.scheduler.lease;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically recovers expired leases. This is the only recovery mechanism. Every API instance
 * may run it, because concurrent sweeps never recover the same attempt twice (see
 * {@link RecoveryService}).
 */
@Component
@ConditionalOnProperty(name = "scheduler.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class RecoverySweeper {

	private final RecoveryService recovery;

	private final int batchSize;

	public RecoverySweeper(RecoveryService recovery, SchedulerProperties properties) {
		this.recovery = recovery;
		this.batchSize = properties.recovery().batchSize();
	}

	@Scheduled(fixedDelayString = "${scheduler.recovery.sweep-interval:5s}")
	public void sweep() {
		// One short transaction per batch; a full batch means there may be more.
		List<RecoveredJob> batch;
		do {
			batch = recovery.recoverExpiredLeases(batchSize);
		}
		while (batch.size() == batchSize);
	}

}
