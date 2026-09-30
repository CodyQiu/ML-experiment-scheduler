-- V3: failures reported by workers.
--
-- A worker can now end its attempt with a failure and say whether it is worth retrying. The
-- attempt row keeps that decision and the reason, so every unsuccessful attempt is explained.

ALTER TABLE attempts ADD COLUMN retryable BOOLEAN;

-- Before V3, only lease expiry ended attempts unsuccessfully, and it always records a reason. A
-- FAILED attempt without one can only come from a job failed by hand under V1; it counts as a
-- terminal failure of unknown cause.
UPDATE attempts SET error_type = 'UNKNOWN' WHERE status IN ('FAILED', 'EXPIRED') AND error_type IS NULL;
UPDATE attempts SET retryable = false WHERE status = 'FAILED';

ALTER TABLE attempts DROP CONSTRAINT attempts_error_only_when_unsuccessful;
ALTER TABLE attempts
    -- Every unsuccessful attempt says why it ended, and only those do.
    ADD CONSTRAINT attempts_error_iff_unsuccessful
        CHECK ((status IN ('FAILED', 'EXPIRED')) = (error_type IS NOT NULL)),
    -- A reported failure always says whether a retry was requested.
    ADD CONSTRAINT attempts_retryable_iff_failed
        CHECK ((status = 'FAILED') = (retryable IS NOT NULL));
