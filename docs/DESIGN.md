# Design

How the scheduler works and why. Anything marked **(planned)** belongs to a later increment and
is not implemented yet. Everything else describes the current code.

## Architecture

```
 researcher (curl, scripts)               worker × N (Python + PyTorch, CPU only)
        │ REST/JSON                              │ HTTP polling: claim → heartbeat → complete / fail
        ▼                                        ▼
 ┌──────────────────────────────────────────────────────────────┐
 │ api: Spring Boot 4.1, Java 21                                │
 │ validation · state transitions · claims · leases · recovery  │
 └──────────────────────────────┬───────────────────────────────┘
                                │ JDBC: explicit SQL, short transactions
                                ▼
                     PostgreSQL 18: all durable state
```

- The API is the only component that touches PostgreSQL. Workers change state only through HTTP
  requests, which the API turns into guarded SQL statements.
- Workers poll for work. No broker is needed at this scale: row locks with `FOR UPDATE SKIP LOCKED`
  make the `jobs` table a correct work queue.
- Users submit configuration data for one fixed task (`synthetic-mlp-v1`), never code or commands.

**Terms.** An **experiment** is a submitted batch: a name, a task, a retry budget, and a list of
jobs. A **job** is one configuration plus a seed to execute. An **attempt** is one claim and
execution of a job. Every claim creates a new attempt id, and only the job's current attempt may
change the job.

## Schema

### V1: MVP (implemented, `V1__create_experiments_and_jobs.sql`)

**`experiments`**: one row per submitted batch.

| Column | Type | Purpose |
|---|---|---|
| `id` | `bigint` identity | Primary key |
| `name` | `text` | Human-readable label |
| `task` | `text` | Fixed task id; decides how configs are validated and executed |
| `max_attempts` | `int` | Total executions allowed per job, including the first |
| `created_at` | `timestamptz` | Submission time (database clock) |

**`jobs`**: one row per (config, seed). This table is also the work queue.

| Column | Type | Purpose |
|---|---|---|
| `id` | `bigint` identity | Primary key; also sets the FIFO claim order |
| `experiment_id` | `bigint` FK | Owning experiment |
| `job_index` | `int` | Position in the submitted list |
| `seed` | `int` | Random seed for this run |
| `config` | `jsonb` | Validated hyperparameters, read and written as a whole |
| `state` | `text` | `QUEUED`, `RUNNING`, `SUCCEEDED`, or `FAILED` |
| `max_attempts` | `int` | Copied from the experiment. It never changes, so the budget checks can live on the row itself |
| `attempt_count` | `int` | Claims so far; also the current attempt's number |
| `current_attempt_id` | `uuid` | Claim identity (fencing token): the attempt allowed to change the job while it runs, and after it ends, the attempt that ended it |
| `worker_id` | `text` | Worker holding the current attempt |
| `val_accuracy` | `double precision` | Ranking metric, as a typed column so it can be sorted and checked |
| `result` | `jsonb` | Full metrics of the accepted result |
| `created_at`, `started_at`, `finished_at` | `timestamptz` | Creation, current-attempt start, terminal time |

**JSONB versus typed columns.** `config` and `result` are JSONB because they are validated in the
API and always read whole. Anything the scheduler filters, sorts, constrains, or locks on is a
typed column: `state`, `attempt_count`, `max_attempts`, `current_attempt_id`, `val_accuracy`, and
the timestamps.

**Constraints.** The database enforces the lifecycle *invariants*. The API enforces *policy
limits*, such as 500 jobs per batch or `learningRate` ≤ 1, which can change without a migration.

| Constraint | Invariant | Why |
|---|---|---|
| `jobs_experiment_job_index_unique` | One job per position in a batch | Stable ordering; its index also serves "jobs of experiment X" |
| `jobs_state_known` | `state` is one of the four states | No typo'd states |
| `jobs_attempt_count_within_budget` | 0 ≤ `attempt_count` ≤ `max_attempts` | Retries can never exceed the budget, even if application code has a bug |
| `jobs_queued_has_attempts_left` | `QUEUED` ⇒ `attempt_count` < `max_attempts` | Every queued job can legally run, so claims never need to check the budget |
| `jobs_claim_fields_match_state` | `QUEUED` ⇔ no attempt id, worker, or start time | A non-queued job was always claimed by someone |
| `jobs_result_iff_succeeded`, `jobs_val_accuracy_iff_succeeded` | Result present ⇔ `SUCCEEDED` | No half-written or orphaned results |
| `jobs_finished_iff_terminal` | `finished_at` present ⇔ terminal state | |
| `jobs_val_accuracy_range` | 0 ≤ `val_accuracy` ≤ 1 | Also rejects NaN |
| `experiments_task_known` | `task` is a known task | Workers can only run known tasks |

**Indexes.**

| Index | Serves |
|---|---|
| Primary keys | Lookups by id |
| `(experiment_id, job_index)`, which is unique | Listing an experiment's jobs in order, and per-experiment progress counts |
| `jobs_queued_idx` on `(id) WHERE state = 'QUEUED'` | The claim query: find the oldest queued job without scanning finished jobs |

There is deliberately no index on `val_accuracy`. A best-of-experiment query reads at most 500
rows through the experiment index. An index would be added only if a measured query needs it.

### V2: reliability (planned)

- `jobs.lease_expires_at timestamptz`. Present exactly when the job is `RUNNING` (CHECK). It is the
  end of the current attempt's authority, in database time.
- **`attempts`**, which records history and backs the invariants at the database level:
  - `id uuid` PK. This is the attempt id handed to the worker, equal to `jobs.current_attempt_id`
    while the attempt runs.
  - `job_id`, `attempt_number` with `UNIQUE (job_id, attempt_number)`.
  - `worker_id`, `claimed_at`, `last_heartbeat_at`, `finished_at`.
  - `status`: `RUNNING`, `SUCCEEDED`, `FAILED`, or `EXPIRED`.
  - `error_type`, `error_message`, `retryable`.
  - Partial unique index `(job_id) WHERE status = 'RUNNING'`: at most one live attempt per job.
  - Partial unique index `(job_id) WHERE status = 'SUCCEEDED'`: at most one accepted success per job.
- `experiments.idempotency_key text UNIQUE` and `experiments.request_fingerprint text`, both set or
  both null (CHECK). The key is global in scope, which is enough for a single-user local service.
- An index on `jobs (lease_expires_at) WHERE state = 'RUNNING'` for the recovery sweep.

## Job lifecycle

```
          claim                                    complete by current attempt
 QUEUED ─────────▶ RUNNING ───────────────────────────────────────────────────▶ SUCCEEDED
   ▲                 │  │
   │                 │  └──────────────────────────────────────────────────────▶ FAILED
   └─────────────────┘   non-retryable failure, or a retryable failure or lease
   retryable failure or  expiry on the final attempt (planned)
   lease expiry with
   attempts left (planned)
```

Every transition is one `UPDATE` whose `WHERE` clause is the guard. The affected-row count decides
whether the transition happened: 1 means accepted, 0 means rejected.

| Transition | Trigger | Guard in the `WHERE` clause | When |
|---|---|---|---|
| QUEUED → RUNNING | worker claim | `state = 'QUEUED'`, row locked with `SKIP LOCKED` | next increment |
| RUNNING → SUCCEEDED | worker complete | `state = 'RUNNING' AND current_attempt_id = :attempt` (M2 adds `AND lease_expires_at > now()`) | next increment |
| RUNNING → QUEUED | retryable fail, or lease expiry | as above, and `attempt_count < max_attempts` | M2 |
| RUNNING → FAILED | non-retryable fail, or budget exhausted | as above | M2 |

`SUCCEEDED` and `FAILED` are terminal. No statement has a guard that matches a terminal row, so an
accepted result can never be overwritten.

## API

**Conventions**

- JSON with camelCase fields. Timestamps are ISO-8601 in UTC. Resource ids are integers, and
  attempt ids are UUIDs.
- Parsing is strict. Unknown fields, wrong JSON types (`"64"` or `20.5` for an integer), and
  duplicate keys return 400. Integer literals are accepted for decimal fields.
- Numbers are values, not spellings. A submitted `0.0001` may be echoed as `1.0E-4`, and PostgreSQL
  JSONB stores it as `0.00010`. All three are the same number. This is why request fingerprints
  (M2) are computed from parsed values, never from JSON text.
- Errors use RFC 9457 problem details (`application/problem+json`) and always include a `code`.
  Field problems add `errors: [{field, message}]` with paths such as `jobs[1].config.epochs`.
  Bean Validation reports every violated constraint at once. A JSON type error stops parsing, so
  only the first one is reported.

| Endpoint | Purpose | Success | Errors | Status |
|---|---|---|---|---|
| `POST /experiments` | Submit a batch | `201` + `Location` | `400`; M2: `409` | implemented |
| `GET /experiments/{id}` | Details + progress counts | `200` | `404` | implemented |
| `GET /experiments/{id}/jobs` | Jobs in `jobIndex` order | `200` | `404` | implemented |
| `GET /jobs/{id}` | One job | `200` | `404` | implemented |
| `GET /actuator/health` | Liveness, including the database | `200` | `503` | implemented |
| `POST /worker/jobs/claim` | Claim the oldest queued job | `200` assignment, `204` no work | `400` | next increment |
| `POST /worker/jobs/{id}/complete` | Report success | `200` | `400`, `404`, `409` | next increment |
| `POST /worker/jobs/{id}/heartbeat` | Renew the lease | `200` | `409` | M2 |
| `POST /worker/jobs/{id}/fail` | Report a failure | `200` | `409` | M2 |
| `GET /experiments/{id}/best?limit=N` | Top successful jobs by `valAccuracy` | `200` | `404` | M2 |

### `POST /experiments`

```json
{
  "name": "small-lr-width-sweep",
  "task": "synthetic-mlp-v1",
  "maxAttempts": 3,
  "jobs": [
    {"seed": 0, "config": {"learningRate": 0.01, "hiddenUnits": 16, "hiddenLayers": 1,
                           "batchSize": 64, "epochs": 20, "optimizer": "adam", "weightDecay": 0.0}}
  ]
}
```

| Field | Rule |
|---|---|
| `name` | required, not blank, ≤ 200 characters |
| `task` | required, `"synthetic-mlp-v1"` |
| `maxAttempts` | optional, 1–10, default 3. Total executions per job, including the first |
| `jobs` | required, 1–500 entries, none null |
| `jobs[i].seed` | required integer, 0–2147483647 |
| `config.learningRate` | required number, 0.00001–1.0 |
| `config.hiddenUnits` | required integer, 1–256 |
| `config.hiddenLayers` | required integer, 1–4 |
| `config.batchSize` | required integer, 8–1024 |
| `config.epochs` | required integer, 1–100 |
| `config.optimizer` | required, `"sgd"` or `"adam"` |
| `config.weightDecay` | required number, 0.0–0.1 |

Every config field is required, so a stored config fully describes its run and never depends on a
default that could change later.

**`201 Created`**, `Location: /experiments/1`. `GET /experiments/{id}` returns the same shape:

```json
{"id": 1, "name": "small-lr-width-sweep", "task": "synthetic-mlp-v1", "maxAttempts": 3,
 "createdAt": "2026-09-29T06:15:16.186697Z",
 "progress": {"total": 6, "queued": 6, "running": 0, "succeeded": 0, "failed": 0}}
```

**`400 Bad Request`**. Nothing is stored:

```json
{"title": "Bad Request", "status": 400, "detail": "Request has 2 invalid field(s)",
 "instance": "/experiments", "code": "VALIDATION_FAILED",
 "errors": [{"field": "jobs[1].config.epochs", "message": "must be greater than or equal to 1"},
            {"field": "jobs[1].config.learningRate", "message": "must be less than or equal to 1.0"}]}
```

**Idempotency-Key (M2).** This is the header's planned behavior:

- Same key with the same normalized request: `200` with the original experiment, and no new rows.
- Same key with a different request: `409 IDEMPOTENCY_KEY_REUSED`.
- Concurrent requests with the same key: exactly one experiment is created.

The mechanism is described under [Transactions, locking, and time](#transactions-locking-and-time).

### `GET /experiments/{id}/jobs` and `GET /jobs/{id}`

```json
{"experimentId": 1, "jobs": [
  {"id": 1, "experimentId": 1, "jobIndex": 0, "seed": 0,
   "config": {"learningRate": 0.001, "hiddenUnits": 16, "hiddenLayers": 1, "batchSize": 64,
              "epochs": 20, "optimizer": "adam", "weightDecay": 0.0},
   "state": "QUEUED", "attemptCount": 0, "maxAttempts": 3, "workerId": null,
   "valAccuracy": null, "result": null,
   "createdAt": "2026-09-29T06:15:16.186697Z", "startedAt": null, "finishedAt": null}]}
```

The list is wrapped in an object so paging or filters can be added without breaking clients.
Responses never include the attempt id: it is the worker's credential for changing the job.

### Worker protocol (next increment: claim and complete)

```
POST /worker/jobs/claim            {"workerId": "worker-1"}   (1–64 chars of [A-Za-z0-9._-])
→ 200 {"jobId": 17, "attemptId": "0b6f…", "attemptNumber": 1, "experimentId": 1,
       "task": "synthetic-mlp-v1", "seed": 0, "config": {…}}
       M2 adds "leaseExpiresAt", "leaseSeconds", "heartbeatIntervalSeconds".
→ 204 No Content: nothing is queued. Poll again after a backoff.

POST /worker/jobs/17/complete
     {"attemptId": "0b6f…", "metrics": {"valAccuracy": 0.9312, "valLoss": 0.2011,
      "trainLoss": 0.1804, "trainingSeconds": 2.41, "epochsCompleted": 20}}
→ 200 {"jobId": 17, "state": "SUCCEEDED"}
→ 409 ATTEMPT_NOT_CURRENT: this attempt no longer owns the job. Stop and discard the result.
      M2 adds 409 LEASE_EXPIRED, 409 RESULT_CONFLICT, and 200 with "replayed": true for an
      identical retry.
```

A `409` is a definitive rejection, and the worker must stop. Timeouts and `5xx` responses are
transient, so the worker retries them with bounded backoff.

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Constraint violations or wrong JSON types; see `errors` |
| `MALFORMED_JSON` | 400 | Body missing, not JSON, duplicate keys, or not an object |
| `BAD_REQUEST` | 400 | Bad path or query parameter (e.g. a non-numeric id) |
| `NOT_FOUND` | 404 | Unknown experiment, job, or route |
| `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, … | 405, 415, … | Spring MVC errors; the code is derived from the status |
| `INTERNAL_ERROR` | 500 | Unexpected; details go to the log, not the client |
| `ATTEMPT_NOT_CURRENT` | 409 | (next) The attempt no longer owns the job |
| `LEASE_EXPIRED`, `RESULT_CONFLICT`, `IDEMPOTENCY_KEY_REUSED` | 409 | (M2) |

## Guarantees

### MVP

| # | Guarantee | Mechanism | Status |
|---|---|---|---|
| G1 | A submission is all-or-nothing | Validation finishes before the transaction opens. The experiment row and all job rows share one transaction, and the repositories require it (`Propagation.MANDATORY`) | implemented, tested |
| G2 | Malformed or out-of-range input is rejected before anything is stored | Strict Jackson settings plus Bean Validation | implemented, tested |
| G3 | Stored jobs never violate lifecycle invariants | CHECK constraints | implemented, tested |
| G4 | Progress counts always agree with each other | One aggregate statement, which reads one snapshot | implemented, tested |
| G5 | Two workers never both claim the same queued job | Single-statement claim with `FOR UPDATE SKIP LOCKED` | next increment |
| G6 | Only the current attempt can complete a job, and a succeeded job is never overwritten | Guarded `UPDATE` plus the affected-row count | next increment |

**The MVP does not guarantee the following** (known limitations, fixed in M2):

- A worker that crashes, or a claim response lost in transit, leaves its job `RUNNING` forever.
- Failures cannot be reported, and there are no retries. `maxAttempts` is stored but unused.
- Submitting the same batch twice creates two experiments.
- A completion retried after a lost response gets `409`, even though its result was stored.

### Reliability milestone (planned)

| # | Guarantee | Mechanism |
|---|---|---|
| R1 | A crashed worker's job becomes eligible again | Lease in database time, renewed by heartbeats. A sweeper re-queues expired attempts |
| R2 | Recovery is safe with several API instances | The sweeper uses `SKIP LOCKED`, and its guards are re-evaluated under the row lock. No leader election |
| R3 | A stale attempt cannot renew, complete, or fail a job | Guards on `current_attempt_id`, `state`, and `lease_expires_at > now()`. Strict leases: an expired attempt loses authority even before the sweeper runs |
| R4 | Retries stop at `maxAttempts`, including lease expiry | The guard plus `jobs_attempt_count_within_budget` |
| R5 | Submissions are idempotent | Unique key, fingerprint, and one transaction |
| R6 | Repeating a completion is safe | Identical replay → `200`, no mutation. Different payload → `409` |
| R7 | At most one accepted success per job | The terminal-state guard plus a partial unique index on `attempts` |

**Delivery semantics.** Execution is at-least-once. A job can run more than once, for example
after a crash, or when a slow worker that is still alive loses its lease. The service accepts at
most one terminal result per job. This is not exactly-once execution.

## Transactions, locking, and time

- **Isolation.** Everything runs at PostgreSQL's default isolation level, READ COMMITTED.
  Correctness comes from single guarded statements and row locks, not from a stricter isolation
  level. When an `UPDATE` waits for a row lock, PostgreSQL re-checks its `WHERE` clause against the
  newly committed row before writing. That makes check-then-write atomic inside one statement.
- **Short transactions.** A transaction never spans training or a network call to a worker. The
  claim commits before its response is sent.
- **Accepted or rejected.** The affected-row count of the guarded `UPDATE` decides. A follow-up read
  may explain a rejection, or recognize a replay of a terminal result, which cannot change any
  more. It never decides to write.
- **Database time.** Lease timestamps and comparisons use PostgreSQL's `now()` inside SQL. Worker
  clocks are never compared with the server's. `now()` is the transaction's start time, and these
  transactions are single statements that last milliseconds.

Planned claim statement (next increment):

```sql
WITH next_job AS (
    SELECT id FROM jobs
    WHERE state = 'QUEUED'
    ORDER BY id
    LIMIT 1
    FOR UPDATE SKIP LOCKED           -- rows other claimers hold are skipped, not waited on
)
UPDATE jobs j
SET state = 'RUNNING', attempt_count = j.attempt_count + 1,
    current_attempt_id = gen_random_uuid(), worker_id = :workerId, started_at = now()
    -- M2 also sets lease_expires_at = now() + lease and inserts the attempts row in this transaction
FROM next_job
WHERE j.id = next_job.id
RETURNING j.id, j.current_attempt_id, j.attempt_count, j.experiment_id, j.seed, j.config;
```

Planned completion guard (next increment):

```sql
UPDATE jobs
SET state = 'SUCCEEDED', val_accuracy = :valAccuracy, result = CAST(:metrics AS jsonb), finished_at = now()
WHERE id = :jobId AND state = 'RUNNING' AND current_attempt_id = :attemptId
  -- M2: AND lease_expires_at > now()
```

**A lost claim response.** The claim committed, but the worker never learned the attempt id. In the
MVP, the job stays `RUNNING` forever. In M2, nobody heartbeats that attempt, so its lease expires
and the sweeper re-queues the job. That costs one lease duration and one attempt from the budget.
The job never runs twice because of this, since nobody executes the lost attempt.

**Leases (M2).** The planned defaults, all configurable:

- The lease lasts 30 s, and the worker heartbeats every 10 s, so two heartbeats can be missed.
- The sweeper runs every 5 s, so an expired attempt is re-queued at most about 35 s after its last
  successful heartbeat.
- The claim response carries the lease and heartbeat settings, so workers follow server policy.
- When the final allowed attempt expires, the job becomes `FAILED` with a "lease expired on final
  attempt" error.

**Idempotent submission (M2).**

1. `INSERT INTO experiments (…, idempotency_key, request_fingerprint) VALUES (…) ON CONFLICT
   (idempotency_key) DO NOTHING RETURNING id` runs inside the submission transaction.
2. A concurrent insert with the same key blocks on the unique index until the first transaction
   finishes, and then inserts nothing.
3. In that case, a follow-up `SELECT` sees the committed row, because READ COMMITTED takes a new
   snapshot per statement, and compares fingerprints.
4. The fingerprint is a SHA-256 over a canonical serialization of the validated request, with
   defaults applied, keys sorted, and numbers formatted from parsed values.

**Multiple API instances.** Claims (`SKIP LOCKED`), sweeps (`SKIP LOCKED` plus re-checked guards),
and Flyway migrations (which take an advisory lock) are all safe to run concurrently.

**Worker execution (planned).**

- Training runs in the worker's main thread. A heartbeat thread renews the lease, and a lost lease
  sets a stop flag that the training loop checks after every minibatch.
- A training subprocess could be killed even while stuck inside native code. The cost is a
  PyTorch import (about 1–2 s) per job, which would distort throughput measurements of small jobs.
  Cooperative stopping is enough because we own the training loop.
- `torch.set_num_threads(1)` and `OMP_NUM_THREADS=1` stop N workers from quietly using N × cores
  threads.

## Decision log

| Decision | Why |
|---|---|
| Spring Boot 4.1.1, Java 21 LTS | 4.1.1 is Spring Initializr's current GA default (3.5.x is no longer offered). It supports Java 17–26 |
| Pinned versions (from the Boot 4.1.1 BOM) | Spring Framework 7.0.9, Jackson 3.1.5, Flyway 12.4.0 (verifies PostgreSQL 18), pgjdbc 42.7.13, Testcontainers 2.0.5, JUnit 6.0.3, Maven 3.9.16 through the wrapper. Images: `postgres:18.6-trixie`, `eclipse-temurin:21.0.12.1_1-{jdk,jre}-noble` |
| `JdbcClient`/`JdbcTemplate` with explicit SQL, no JPA | The locking and guard semantics are the core of the project and stay visible in the SQL |
| Strict Jackson settings | Jackson 3 ignores unknown fields by default. A typo'd hyperparameter must fail instead of running defaults |
| UUID attempt ids as fencing tokens | Unguessable, never reused (even if a counter were ever reset), and able to serve as the `attempts` primary key |
| `max_attempts` copied onto `jobs` | Makes the budget invariants row-local CHECK constraints |
| Invariants in the database, policy limits in the API | Invariants must hold for every code path. Limits are tunable |
| Progress derived from jobs with one aggregate query | There are no stored counters to keep in sync, and the result is consistent by construction |
| Polling with `204 No Content` for no work | The simplest protocol. Backoff with jitter avoids busy-waiting |
| Recovery by periodic sweeper, not lazily on claim (planned) | Jobs whose final attempt expired become `FAILED` promptly, and progress stays truthful |
| Java builds can run in Docker (`scripts/mvnw-docker.sh`) | The dev machine has no JDK. The container uses the same JDK image as the runtime |
