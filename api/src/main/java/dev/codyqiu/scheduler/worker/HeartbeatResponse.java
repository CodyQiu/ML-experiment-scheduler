package dev.codyqiu.scheduler.worker;

import java.time.Instant;

/** {@code leaseExpiresAt} is database time and only informational; workers pace by interval. */
public record HeartbeatResponse(long jobId, Instant leaseExpiresAt) {
}
