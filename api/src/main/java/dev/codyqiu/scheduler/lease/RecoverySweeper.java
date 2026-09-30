package dev.codyqiu.scheduler.lease;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Periodically recovers expired leases. This is the only recovery mechanism. Every API instance
 * may run it, because concurrent sweeps never recover the same attempt twice (see
 * {@link RecoveryService}).
 *
 * <p>The schedule comes from {@link SchedulerProperties}, like the lease settings, so one binding
 * path governs all of the policy. A {@code @Scheduled} placeholder would resolve environment
 * variables by different rules.
 */
@Component
@ConditionalOnProperty(name = "scheduler.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class RecoverySweeper implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

	private final RecoveryService recovery;

	private final SchedulerProperties properties;

	public RecoverySweeper(RecoveryService recovery, SchedulerProperties properties) {
		this.recovery = recovery;
		this.properties = properties;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		Duration interval = properties.recovery().sweepInterval();
		log.atInfo()
			.addKeyValue("event.action", "recovery.policy")
			.addKeyValue("sweepIntervalSeconds", seconds(interval))
			.addKeyValue("batchSize", properties.recovery().batchSize())
			.addKeyValue("leaseSeconds", properties.lease().durationSeconds())
			.addKeyValue("heartbeatIntervalSeconds", properties.lease().heartbeatIntervalSeconds())
			.log("Recovering expired leases every {} s in batches of {} (leases last {} s, heartbeats every {} s)",
					seconds(interval), properties.recovery().batchSize(), properties.lease().durationSeconds(),
					properties.lease().heartbeatIntervalSeconds());
		registrar.addFixedDelayTask(this::sweep, interval);
	}

	void sweep() {
		// One short transaction per batch; a full batch means there may be more.
		int batchSize = properties.recovery().batchSize();
		List<RecoveredJob> batch;
		do {
			batch = recovery.recoverExpiredLeases(batchSize);
		}
		while (batch.size() == batchSize);
	}

	private static double seconds(Duration duration) {
		return duration.toMillis() / 1000.0;
	}

}
