-- V4: idempotent submission.
--
-- A client may send an Idempotency-Key with POST /experiments. The key identifies one intended
-- submission; the fingerprint identifies what was submitted under it. The key's scope is global,
-- which is enough for a single-user local service, and keys do not expire.

ALTER TABLE experiments
    ADD COLUMN idempotency_key     TEXT,
    ADD COLUMN request_fingerprint TEXT,
    -- At most one experiment per key. NULLs never conflict, so submissions without a key are
    -- unaffected. INSERT ... ON CONFLICT relies on this constraint to detect a duplicate, including
    -- one whose transaction has not committed yet.
    ADD CONSTRAINT experiments_idempotency_key_unique UNIQUE (idempotency_key),
    ADD CONSTRAINT experiments_key_has_fingerprint
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL));
