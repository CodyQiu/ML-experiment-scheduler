package dev.codyqiu.scheduler.experiment;

/** The experiment an Idempotency-Key already belongs to, and the fingerprint it was created with. */
record StoredKey(long experimentId, String fingerprint) {
}
