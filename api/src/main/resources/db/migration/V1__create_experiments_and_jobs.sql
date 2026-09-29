-- V1: MVP schema.
--
-- An experiment is a submitted batch; each job is one (config, seed) pair to execute.
-- Leases, attempt history, and idempotency keys arrive in a later migration.
--
-- The CHECK constraints below encode job invariants that must hold no matter which code
-- path writes the row. Policy limits (batch size, hyperparameter ranges, name length) are
-- enforced by the API instead, so they can change without a migration.

CREATE TABLE experiments (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT        NOT NULL,
    task         TEXT        NOT NULL,
    max_attempts INTEGER     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT experiments_name_not_empty CHECK (char_length(name) > 0),
    -- Workers can only execute known tasks; adding a task is a code change plus a migration.
    CONSTRAINT experiments_task_known CHECK (task IN ('synthetic-mlp-v1')),
    CONSTRAINT experiments_max_attempts_positive CHECK (max_attempts >= 1)
);

CREATE TABLE jobs (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    experiment_id      BIGINT           NOT NULL REFERENCES experiments (id),
    job_index          INTEGER          NOT NULL,
    seed               INTEGER          NOT NULL,
    config             JSONB            NOT NULL,
    state              TEXT             NOT NULL DEFAULT 'QUEUED',
    -- Copied from the experiment (immutable) so the budget invariants below are row-local.
    max_attempts       INTEGER          NOT NULL,
    -- Number of claims so far; also the attempt number of the current attempt.
    attempt_count      INTEGER          NOT NULL DEFAULT 0,
    -- Claim identity (fencing token) minted by each claim. Only this attempt may change
    -- the job while it is RUNNING; after a terminal state it identifies the final attempt.
    current_attempt_id UUID,
    worker_id          TEXT,
    -- Ranking metric of the accepted result: a typed column so it can be sorted and checked.
    val_accuracy       DOUBLE PRECISION,
    -- Full metrics object of the accepted result.
    result             JSONB,
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,

    -- Stable position within the batch. Its index also serves "list jobs of experiment X".
    CONSTRAINT jobs_experiment_job_index_unique UNIQUE (experiment_id, job_index),
    CONSTRAINT jobs_job_index_nonnegative CHECK (job_index >= 0),
    CONSTRAINT jobs_seed_nonnegative CHECK (seed >= 0),
    CONSTRAINT jobs_config_is_object CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT jobs_state_known CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT jobs_max_attempts_positive CHECK (max_attempts >= 1),
    CONSTRAINT jobs_attempt_count_within_budget CHECK (attempt_count BETWEEN 0 AND max_attempts),
    -- A queued job must be runnable, so it needs at least one attempt left.
    CONSTRAINT jobs_queued_has_attempts_left CHECK (state <> 'QUEUED' OR attempt_count < max_attempts),
    -- Claim fields exist exactly when the job is not queued: every non-queued job was claimed.
    CONSTRAINT jobs_claim_fields_match_state CHECK (
        CASE
            WHEN state = 'QUEUED' THEN num_nonnulls(current_attempt_id, worker_id, started_at) = 0
            ELSE num_nulls(current_attempt_id, worker_id, started_at) = 0 AND attempt_count >= 1
        END),
    -- A result exists exactly for succeeded jobs.
    CONSTRAINT jobs_result_iff_succeeded CHECK ((state = 'SUCCEEDED') = (result IS NOT NULL)),
    CONSTRAINT jobs_val_accuracy_iff_succeeded CHECK ((state = 'SUCCEEDED') = (val_accuracy IS NOT NULL)),
    CONSTRAINT jobs_result_is_object CHECK (jsonb_typeof(result) = 'object'),
    CONSTRAINT jobs_val_accuracy_range CHECK (val_accuracy BETWEEN 0 AND 1),
    CONSTRAINT jobs_finished_iff_terminal CHECK ((state IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL))
);

-- Claim path: "oldest QUEUED job". The partial index contains only queued jobs, so the
-- claim query stays an index lookup no matter how many finished jobs accumulate.
CREATE INDEX jobs_queued_idx ON jobs (id) WHERE state = 'QUEUED';
