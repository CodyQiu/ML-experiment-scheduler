package dev.codyqiu.scheduler.lease;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Lease and recovery policy. Environment variables override the defaults, e.g.
 * {@code SCHEDULER_LEASE_DURATION=10s}.
 */
@ConfigurationProperties("scheduler")
public record SchedulerProperties(@DefaultValue Lease lease, @DefaultValue Recovery recovery) {

	/**
	 * @param duration how long a claim or heartbeat keeps an attempt's authority
	 * @param heartbeatInterval how often workers are told to renew; at most half the duration, so
	 * one late or lost heartbeat does not cost the lease
	 */
	public record Lease(@DefaultValue("30s") Duration duration, @DefaultValue("10s") Duration heartbeatInterval) {

		public Lease {
			if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()
					|| heartbeatInterval.multipliedBy(2).compareTo(duration) > 0) {
				throw new IllegalArgumentException("scheduler.lease.heartbeat-interval (" + heartbeatInterval
						+ ") must be positive and at most half of scheduler.lease.duration (" + duration + ")");
			}
		}

		public double durationSeconds() {
			return duration.toMillis() / 1000.0;
		}

		public double heartbeatIntervalSeconds() {
			return heartbeatInterval.toMillis() / 1000.0;
		}

	}

	/**
	 * @param enabled whether this instance runs the periodic sweep (tests turn it off and sweep
	 * explicitly)
	 * @param sweepInterval pause between sweeps
	 * @param batchSize jobs recovered per transaction, so a large backlog never holds many row locks
	 * at once
	 */
	public record Recovery(@DefaultValue("true") boolean enabled, @DefaultValue("5s") Duration sweepInterval,
			@DefaultValue("100") int batchSize) {
	}

}
