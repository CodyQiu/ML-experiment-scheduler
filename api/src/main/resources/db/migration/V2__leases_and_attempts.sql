-- V2: leases and attempt history.
--
-- A RUNNING job now holds a lease. Its current attempt keeps authority only while
-- lease_expires_at is in the future, by database time. Every claim also records an attempts row,
-- so each execution of a job can be explained afterwards.

ALTER TABLE jobs ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- Jobs RUNNING under V1 had no lease, so nothing could ever recover them. Giving them an
-- already-expired lease lets the first recovery sweep re-queue them, or fail them if their budget
-- is spent.
UPDATE jobs SET lease_expires_at = now() WHERE state = 'RUNNING';

ALTER TABLE jobs
    ADD CONSTRAINT jobs_lease_iff_running CHECK ((state = 'RUNNING') = (lease_expires_at IS NOT NULL));

-- Recovery sweep: RUNNING jobs whose lease has passed, oldest first.
CREATE INDEX jobs_running_lease_idx ON jobs (lease_expires_at) WHERE state = 'RUNNING';

CREATE TABLE attempts (
    -- The attempt id handed to the worker; equals jobs.current_attempt_id while the attempt runs.
    id                UUID        PRIMARY KEY,
    job_id            BIGINT      NOT NULL REFERENCES jobs (id),
    attempt_number    INTEGER     NOT NULL,
    worker_id         TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'RUNNING',
    claimed_at        TIMESTAMPTZ NOT NULL,
    last_heartbeat_at TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    error_type        TEXT,
    error_message     TEXT,

    -- Also serves "attempt history of job X", in order.
    CONSTRAINT attempts_job_attempt_number_unique UNIQUE (job_id, attempt_number),
    CONSTRAINT attempts_attempt_number_positive CHECK (attempt_number >= 1),
    CONSTRAINT attempts_status_known CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    CONSTRAINT attempts_finished_iff_ended CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT attempts_error_only_when_unsuccessful CHECK (status IN ('FAILED', 'EXPIRED') OR error_type IS NULL)
);

-- At most one live attempt, and at most one accepted success, per job. The database enforces
-- these whatever the application code does.
CREATE UNIQUE INDEX attempts_one_running_per_job ON attempts (job_id) WHERE status = 'RUNNING';
CREATE UNIQUE INDEX attempts_one_success_per_job ON attempts (job_id) WHERE status = 'SUCCEEDED';

-- Backfill: every job that was ever claimed gets a row for its latest attempt, whose status
-- mirrors the job's state. V1 kept no record of earlier attempts, so those cannot be rebuilt.
INSERT INTO attempts (id, job_id, attempt_number, worker_id, status, claimed_at, finished_at)
SELECT current_attempt_id, id, attempt_count, worker_id, state, started_at, finished_at
FROM jobs
WHERE state <> 'QUEUED';
