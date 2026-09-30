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

### V2: leases and attempt history (implemented, `V2__leases_and_attempts.sql`)

**`jobs.lease_expires_at timestamptz`.** The end of the current attempt's authority, in database
time.

- `jobs_lease_iff_running` makes it present exactly when the job is `RUNNING`.
- The partial index `jobs_running_lease_idx` on `(lease_expires_at) WHERE state = 'RUNNING'` serves
  the recovery sweep.

**`attempts`**: one row per claim. It records history, and it backs two invariants at the database
level.

| Column | Purpose |
|---|---|
| `id uuid` PK | The attempt id handed to the worker; equals `jobs.current_attempt_id` while the attempt runs |
| `job_id`, `attempt_number` | `UNIQUE (job_id, attempt_number)`, which also serves "history of job X" in order |
| `worker_id`, `claimed_at`, `last_heartbeat_at`, `finished_at` | Who ran it, and when |
| `status` | `RUNNING`, `SUCCEEDED`, `FAILED`, or `EXPIRED`. `finished_at` is present exactly when the status is not `RUNNING` |
| `error_type`, `error_message` | Why it ended unsuccessfully (e.g. `LEASE_EXPIRED`). Allowed only for `FAILED` or `EXPIRED` |

- The partial unique index `attempts_one_running_per_job` allows at most one live attempt per job.
- The partial unique index `attempts_one_success_per_job` allows at most one accepted success per
  job.

**The migration handles existing rows** (covered by `MigrationTests`):

- Jobs that were `RUNNING` under V1 had no lease and could never be recovered. They get an
  already-expired lease, so the first sweep recovers them.
- Every claimed job gets an attempts row for its latest attempt, mirroring the job's state.
  Recovery needs that row: it marks the attempt `EXPIRED` and checks that exactly one row changed.

### V3: reported failures (implemented, `V3__reported_failures.sql`)

- **`attempts.retryable boolean`** records the worker's judgment on a reported failure.
- **`attempts_error_iff_unsuccessful`** replaces V2's weaker constraint. Every `FAILED` or
  `EXPIRED` attempt must carry an `error_type`, and no other attempt may.
- **`attempts_retryable_iff_failed`**: a reported failure always says whether a retry was
  requested.
- **Backfill:** a `FAILED` attempt without a reason can only come from a job failed by hand under
  V1. It becomes `UNKNOWN` and `retryable = false`. `MigrationTests` covers this.

### Planned (2.3)

`experiments.idempotency_key text UNIQUE` and `experiments.request_fingerprint
text`, both set or both null. The key is global in scope, which is enough for a single-user local
service.

## Job lifecycle

```
          claim                                    complete by current attempt
 QUEUED ─────────▶ RUNNING ───────────────────────────────────────────────────▶ SUCCEEDED
   ▲                 │  │
   │                 │  └──────────────────────────────────────────────────────▶ FAILED
   └─────────────────┘   lease expiry on the final attempt;
   lease expiry with     a non-retryable failure; a retryable
   attempts left;        failure on the final attempt
   a retryable failure
   with attempts left
```

Every transition is one `UPDATE` whose `WHERE` clause is the guard. The affected-row count decides
whether the transition happened: 1 means accepted, 0 means rejected. Each transition also updates
the attempt's row in the same transaction.

| Transition | Trigger | Guard in the `WHERE` clause | When |
|---|---|---|---|
| QUEUED → RUNNING | worker claim | `state = 'QUEUED'`, row locked with `SKIP LOCKED`; the lease starts | implemented |
| RUNNING → RUNNING | heartbeat (renews the lease) | `state = 'RUNNING' AND current_attempt_id = :attempt AND lease_expires_at > now()` | implemented |
| RUNNING → SUCCEEDED | worker complete | `state = 'RUNNING' AND current_attempt_id = :attempt AND lease_expires_at > now()` | implemented |
| RUNNING → QUEUED | lease expiry with attempts left | the row is locked by the sweep (`state = 'RUNNING' AND lease_expires_at <= now()`, `SKIP LOCKED`), then `attempt_count < max_attempts` | implemented |
| RUNNING → FAILED | lease expiry on the final attempt | the same lock, then `attempt_count >= max_attempts` | implemented |
| RUNNING → QUEUED | retryable failure with attempts left | the running attempt's guard, and `:retryable AND attempt_count < max_attempts` | implemented |
| RUNNING → FAILED | non-retryable failure, or a retryable one on the final attempt | the running attempt's guard, and the negation | implemented |

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
  (2.3) are computed from parsed values, never from JSON text.
- Errors use RFC 9457 problem details (`application/problem+json`) and always include a `code`.
  Field problems add `errors: [{field, message}]` with paths such as `jobs[1].config.epochs`.
  Bean Validation reports every violated constraint at once. A JSON type error stops parsing, so
  only the first one is reported.

| Endpoint | Purpose | Success | Errors | Status |
|---|---|---|---|---|
| `POST /experiments` | Submit a batch | `201` + `Location` | `400`; 2.3: `409` | implemented |
| `GET /experiments/{id}` | Details + progress counts | `200` | `404` | implemented |
| `GET /experiments/{id}/jobs` | Jobs in `jobIndex` order | `200` | `404` | implemented |
| `GET /jobs/{id}` | One job | `200` | `404` | implemented |
| `GET /actuator/health` | Liveness, including the database | `200` | `503` | implemented |
| `POST /worker/jobs/claim` | Claim the oldest queued job | `200` assignment, `204` no work | `400` | implemented |
| `POST /worker/jobs/{id}/complete` | Report success | `200` | `400`, `404`, `409` | implemented |
| `POST /worker/jobs/{id}/heartbeat` | Renew the lease | `200` | `400`, `404`, `409` | implemented |
| `POST /worker/jobs/{id}/fail` | Report a failure | `200` with the new state | `400`, `404`, `409` | implemented |
| `GET /jobs/{id}/attempts` | Attempt history | `200` | `404` | implemented |
| `GET /experiments/{id}/best?limit=N` | Top successful jobs by `valAccuracy` | `200` | `404` | 2.3 |

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

**Idempotency-Key (2.3).** This is the header's planned behavior:

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
   "createdAt": "2026-09-29T06:15:16.186697Z", "startedAt": null, "finishedAt": null,
   "leaseExpiresAt": null, "lastError": null}]}
```

`lastError` is `{attemptNumber, type, message}` from the most recent unsuccessful attempt, or null.
It stays after a later success, so the list shows at a glance which jobs needed retries and why.
The query takes it with a `LEFT JOIN LATERAL` that reads each job's attempts newest first through
the `(job_id, attempt_number)` index.

The list is wrapped in an object so paging or filters can be added without breaking clients.
Responses never include the attempt id: it is the worker's credential for changing the job.

### Worker protocol: claim, heartbeat, complete (implemented)

**Claim.** `POST /worker/jobs/claim` with `{"workerId": "worker-1"}`. The `workerId` is 1–64
characters of `[A-Za-z0-9._-]`.

```json
200 OK
{"jobId": 1, "attemptId": "8b64bfc6-4e60-470c-94aa-234862d4287e", "attemptNumber": 1,
 "experimentId": 1, "task": "synthetic-mlp-v1", "seed": 0,
 "config": {"learningRate": 0.001, "hiddenUnits": 16, "hiddenLayers": 1, "batchSize": 64,
            "epochs": 20, "optimizer": "adam", "weightDecay": 0.0},
 "leaseExpiresAt": "2026-09-29T16:47:57.412Z", "leaseSeconds": 30.0, "heartbeatIntervalSeconds": 10.0}
```

- `204 No Content` means no queued job could be locked at that moment. The worker polls again
  after a backoff.
- A `204` can be momentarily pessimistic: if every remaining queued job is locked by a concurrent
  claim, the claimer skips them all. Those claims will commit, so no work is lost.
- **The lease starts at the claim.** The worker must heartbeat every `heartbeatIntervalSeconds`,
  pacing by its own elapsed time.
- `leaseExpiresAt` is database time and only informational: a worker never compares it with its own
  clock.

**Heartbeat.** `POST /worker/jobs/{jobId}/heartbeat` with `{"attemptId": "…"}`.

- `200 {"jobId": 1, "leaseExpiresAt": "…"}`: the lease now ends `leaseSeconds` from the database's
  `now()`.
- `409 LEASE_EXPIRED`: the attempt still holds the running job, but its lease has passed. Leases are
  strict, so an expired attempt cannot revive itself even before recovery runs.
- `409 ATTEMPT_NOT_CURRENT`: the job was reassigned or finished, or this was never its attempt. A
  stale attempt can therefore never renew the lease of the attempt that replaced it.
- On either `409`, the worker must stop working on the job.

**Complete.** `POST /worker/jobs/{jobId}/complete`:

```json
{"attemptId": "8b64bfc6-4e60-470c-94aa-234862d4287e",
 "metrics": {"valAccuracy": 0.91, "valLoss": 0.25, "trainLoss": 0.2, "trainingSeconds": 1.5}}
```

| Metric | Rule |
|---|---|
| `valAccuracy` | 0.0–1.0. The fraction of the fixed validation split that the final model classifies correctly. This is the ranking metric |
| `valLoss`, `trainLoss` | 0–1000000 |
| `trainingSeconds` | 0–86400. Measured by the worker |

The upper bounds also reject non-finite values. JSON has no NaN, but an overflowing literal such as
`1e400` parses as infinity.

- `200 {"jobId": 1, "state": "SUCCEEDED"}`: the result is accepted and final.
- `409 LEASE_EXPIRED`: the lease passed before the report arrived. It is rejected even if recovery
  has not run yet, and nothing changed.
- `409 ATTEMPT_NOT_CURRENT`: the attempt does not own a running job, and nothing changed. The body
  includes `jobState`, for example `RUNNING` when another attempt holds the job, or `SUCCEEDED`.
- On either `409`, the worker must discard its result.
- `404 NOT_FOUND`: no such job.
- `400`: invalid report. Validation runs before any state is read.
**Fail.** `POST /worker/jobs/{jobId}/fail`:

```json
{"attemptId": "8b64bfc6-…", "retryable": false, "errorType": "TRAINING_DIVERGED",
 "message": "validation loss is nan"}
```

| Field | Rule |
|---|---|
| `attemptId` | required UUID |
| `retryable` | required boolean. This is the worker's judgment; the budget has the last word |
| `errorType` | required `UPPER_SNAKE_CASE` code, ≤ 64 characters |
| `message` | required, ≤ 2000 characters (the worker truncates) |

- The guard is the same as for completion: the job is `RUNNING`, the attempt is current, and its
  lease is live.
- `200 {"jobId": 2, "state": "QUEUED"}`: another attempt will run. That happens only when the
  failure is retryable and attempts remain.
- `200 {"jobId": 2, "state": "FAILED"}`: the job has ended.
- `409 LEASE_EXPIRED` or `409 ATTEMPT_NOT_CURRENT`: nothing changed. A late failure report from an
  expired attempt leaves no trace; recovery records that attempt as `EXPIRED`.

**Attempt history.** `GET /jobs/{id}/attempts` returns
`{"jobId": 1, "attempts": [{attemptNumber, workerId, status, claimedAt, lastHeartbeatAt, finishedAt,
errorType, errorMessage, retryable}]}`, oldest first. It deliberately omits attempt ids: a running
attempt's id is its worker's credential.

- Increment 2.3 adds `409 RESULT_CONFLICT`, and returns `200` with `"replayed": true` for an
  identical retry.

A `409` is a definitive rejection, so the worker must stop. Timeouts and `5xx` responses are
transient, so the worker retries them with bounded backoff. In the MVP, a retried completion whose
first delivery succeeded gets `409` with `jobState: SUCCEEDED`, and the worker can treat that as
"already recorded."

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Constraint violations or wrong JSON types; see `errors` |
| `MALFORMED_JSON` | 400 | Body missing, not JSON, duplicate keys, or not an object |
| `BAD_REQUEST` | 400 | Bad path or query parameter (e.g. a non-numeric id) |
| `NOT_FOUND` | 404 | Unknown experiment, job, or route |
| `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, … | 405, 415, … | Spring MVC errors; the code is derived from the status |
| `INTERNAL_ERROR` | 500 | Unexpected; details go to the log, not the client |
| `ATTEMPT_NOT_CURRENT` | 409 | The attempt does not own a running job; `jobId` and `jobState` are included |
| `LEASE_EXPIRED` | 409 | The attempt holds the running job, but its lease has passed (strict leases) |
| `RESULT_CONFLICT`, `IDEMPOTENCY_KEY_REUSED` | 409 | (2.3) |

## Guarantees

### MVP

| # | Guarantee | Mechanism | Status |
|---|---|---|---|
| G1 | A submission is all-or-nothing | Validation finishes before the transaction opens. The experiment row and all job rows share one transaction, and the repositories require it (`Propagation.MANDATORY`) | implemented, tested |
| G2 | Malformed or out-of-range input is rejected before anything is stored | Strict Jackson settings plus Bean Validation | implemented, tested |
| G3 | Stored jobs never violate lifecycle invariants | CHECK constraints | implemented, tested |
| G4 | Progress counts always agree with each other | One aggregate statement, which reads one snapshot | implemented, tested |
| G5 | Two workers never both claim the same queued job | Single-statement claim with `FOR UPDATE SKIP LOCKED` | implemented, tested |
| G6 | Only the current attempt can complete a job, and a succeeded job is never overwritten | Guarded `UPDATE` plus the affected-row count | implemented, tested |

**The MVP alone did not guarantee the following.** Leases (2.1) fixed the first; the rest remain:

- ~~A worker that crashes, or a claim response lost in transit, leaves its job `RUNNING`
  forever.~~ Fixed by R1.
- ~~Failures cannot be reported.~~ Fixed in 2.2 (`/fail`; R4).
- Submitting the same batch twice creates two experiments (2.3).
- A completion retried after a lost response gets `409`, even though its result was stored (2.3).

### Reliability milestone

| # | Guarantee | Mechanism | Status |
|---|---|---|---|
| R1 | A crashed worker's job becomes eligible again | A lease in database time, renewed by heartbeats. A periodic sweep re-queues expired attempts | implemented; tested, and verified live with SIGKILL and `docker pause` |
| R2 | Recovery is safe with several API instances | The sweep locks rows with `SKIP LOCKED` and re-checks expiry under the lock. No leader election | implemented; 8 concurrent sweeps recover each attempt exactly once |
| R3 | A stale attempt cannot renew or complete a job, or replace a newer attempt's lease | Guards on `current_attempt_id`, `state`, and `lease_expires_at > now()`. Leases are strict: an expired attempt loses authority even before the sweep runs | implemented, tested, including `fail` |
| R4 | Retries stop at `maxAttempts`, however attempts end | The fail guard re-queues only when `retryable AND attempt_count < max_attempts`, and the sweep fails a job whose final attempt expired. `jobs_queued_has_attempts_left` backs both: a mutation that ignored the budget was stopped by this CHECK | implemented; tested through failures alone and through a mix of expiries and failures |
| R5 | Submissions are idempotent | Unique key, fingerprint, and one transaction | 2.3 |
| R6 | Repeating a completion is safe | Identical replay → `200`, no mutation. Different payload → `409` | 2.3 |
| R7 | At most one accepted success per job | The terminal-state guard plus `attempts_one_success_per_job` | implemented, tested |
| R8 | A completion racing lease-expiry recovery: exactly one wins | Both sides lock the same row; the loser's guard fails on re-check (or `SKIP LOCKED` passes over it) | implemented; both orders forced deterministically in tests |

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

**The claim statement** (`JobRepository.claimNext`):

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
    current_attempt_id = gen_random_uuid(), worker_id = :workerId, started_at = now(),
    lease_expires_at = now() + make_interval(secs => :leaseSeconds)
FROM next_job, experiments e         -- experiments only supplies the task; its rows are not locked
WHERE j.id = next_job.id AND e.id = j.experiment_id
RETURNING j.id, j.current_attempt_id, j.attempt_count, j.experiment_id, e.task, j.seed, j.config,
          j.lease_expires_at;
```

The same transaction then inserts the `attempts` row. The job row and its attempt become visible
together, when the transaction commits.

Its plan, measured on 506 jobs, is
`Limit → LockRows → Index Scan using jobs_queued_idx (Filter: state = 'QUEUED')`, followed by
primary-key joins:

1. The partial index yields queued jobs in id order.
2. `LockRows` locks each candidate and skips any that another claim holds.
3. `Limit` stops at the first row it locks.

The `Filter` re-checks `state`, because an index entry can point to a row version that has since
stopped being queued.

**Why the lock clause is what makes claims correct.** A mutation test removed
`FOR UPDATE SKIP LOCKED`, and the concurrency tests failed.

- Without the lock, concurrent claims all picked the same oldest job from their snapshots. Each
  loser then blocked on the winner's row lock.
- When PostgreSQL re-checked the loser's `WHERE` clause (`j.id = next_job.id …`), it still matched,
  because it never mentions `state`. So the loser claimed the job again, as attempt 2, then 3.
- Those double assignments were silent. Errors appeared only once four or more claimers piled onto
  one job and `attempt_count` exceeded `max_attempts` in the CHECK constraint.
- So the constraint is a backstop, and the row lock is the mechanism.
- A second mutation removed only `SKIP LOCKED`, keeping `FOR UPDATE`, and the concurrency tests
  still passed. A claimer that meets a locked row waits for the holder to commit, and PostgreSQL
  then re-checks `state = 'QUEUED'` on the updated row and moves on to the next one.
- `SKIP LOCKED` therefore buys throughput, because claimers never wait on each other. It does not
  buy correctness. Throughput itself has not been measured yet.

**The completion guard** (`JobRepository.markSucceeded`). One updated row means accepted:

```sql
UPDATE jobs
SET state = 'SUCCEEDED', val_accuracy = :valAccuracy, result = CAST(:result AS jsonb),
    finished_at = now(), lease_expires_at = NULL
WHERE id = :jobId AND state = 'RUNNING' AND current_attempt_id = :attemptId
  AND lease_expires_at > now()
```

The same transaction marks the attempt `SUCCEEDED`.

Duplicate deliveries of one attempt's report race on this row. The first `UPDATE` to lock it wins.
Each of the others waits, then its re-checked `state = 'RUNNING'` fails, so it updates 0 rows. The
concurrency test fires 8 simultaneous deliveries: exactly one is accepted, and the stored result is
that one's.

The heartbeat (`JobRepository.renewLease`) uses the same guard and sets
`lease_expires_at = now() + lease`.

**The failure guard** (`JobRepository.markFailed`) checks authority and decides the outcome in one
statement:

```sql
UPDATE jobs
SET state              = CASE WHEN :retry AND attempt_count < max_attempts THEN 'QUEUED' ELSE 'FAILED' END,
    current_attempt_id = CASE WHEN :retry AND attempt_count < max_attempts THEN NULL ELSE current_attempt_id END,
    …                  -- worker_id, started_at, finished_at likewise
    lease_expires_at   = NULL
WHERE id = :jobId AND state = 'RUNNING' AND current_attempt_id = :attemptId AND lease_expires_at > now()
RETURNING state, attempt_count, max_attempts
```

- The budget is read under the same row lock that authorizes the write, so no separate read can go
  stale.
- PostgreSQL evaluates every `SET` expression against the *old* row. Each `CASE` therefore sees the
  same `attempt_count`, whatever order the assignments are written in. (MySQL, by contrast,
  applies single-table assignments left to right.)
- The same transaction marks the attempt `FAILED`, with `error_type`, `error_message`, and
  `retryable`.

**Leases.** These are the defaults. They are set in `SchedulerProperties` and overridable with
environment variables such as `SCHEDULER_LEASE_DURATION`.

| Setting | Default | Notes |
|---|---|---|
| `scheduler.lease.duration` | 30 s | |
| `scheduler.lease.heartbeat-interval` | 10 s | Must be at most half the duration; startup fails otherwise. Two consecutive heartbeats can be missed |
| `scheduler.recovery.sweep-interval` | 5 s | |
| `scheduler.recovery.batch-size` | 100 | Jobs per recovery transaction |

- An attempt that stops heartbeating is re-queued within about lease + sweep interval (≤ 35 s)
  after the moment its lease was last set.
- Measured live: a worker was SIGKILLed about 3.5 s after claiming a job, before its first
  heartbeat. The job was re-queued 30 s after the kill and reclaimed a second later.
- Workers receive the lease settings in the claim response, so server policy is the only source.

**Recovery** (`RecoveryService.recoverExpiredLeases`). Each batch is one transaction of four
statements:

1. `SELECT … WHERE state = 'RUNNING' AND lease_expires_at <= now() ORDER BY lease_expires_at LIMIT n
   FOR UPDATE SKIP LOCKED` locks the expired rows. Rows that a completion, a heartbeat, or another
   sweep holds are skipped. A row that changed after the snapshot is re-checked against the new
   version, so a lease renewed a moment ago no longer matches.
2. `UPDATE … SET state = 'QUEUED', current_attempt_id = NULL, … WHERE id IN (…) AND
   attempt_count < max_attempts` returns jobs with attempts left to the queue.
3. `UPDATE … SET state = 'FAILED', finished_at = now() WHERE id IN (…) AND attempt_count >=
   max_attempts` fails jobs whose final attempt expired. They keep that attempt's identity.
4. `UPDATE attempts SET status = 'EXPIRED', error_type = 'LEASE_EXPIRED' …` closes the expired
   attempts.

The service then checks that steps 2 and 3 changed every locked row, and that step 4 changed
exactly one attempt per job. If either check fails, jobs and attempts have diverged, and the
exception rolls back all four writes.

- **Why lock first and update second** (unlike the one-statement claim): the sweep needs the old
  attempt id, which step 2 erases, and it must split the rows into re-queued and failed. Holding
  the locks from step 1 until commit means nothing can change the rows in between.
- **Why a periodic sweep, not recovery during claims.** A job whose final attempt expired must
  become `FAILED` promptly even if nobody claims anything. The sweep keeps progress counts honest.
  It is the only recovery mechanism.
- **Every API instance may sweep.** Mutation-tested: removing the lock clause makes concurrent
  sweeps collide.

**Completion versus recovery.** The boundary case:

- A completion's transaction starts at t₁, just before the lease ends at L. Recovery's transaction
  starts at t₂, just after (t₁ < L < t₂).
- `now()` is fixed per transaction. The completion sees a live lease and recovery sees an expired
  one, so both guards pass, each by its own clock. Only the row lock decides.
- **If recovery locks first:** the completion's `UPDATE` waits. When recovery commits, the
  completion re-checks against the re-queued row, updates 0 rows, and is rejected with
  `ATTEMPT_NOT_CURRENT`.
- **If the completion locks first:** recovery's `SKIP LOCKED` passes over the row, and the result
  stands. The next sweep sees `SUCCEEDED` and ignores it.
- `ReportRecoveryRaceTests` forces both orders deterministically, for both kinds of report
  (completion and non-retryable failure), since they use the same guard. Each side runs in a held
  transaction, the lease is placed between the two start times, and the test waits for
  PostgreSQL's `pg_stat_activity` to report the lock wait.
- Consequence: an attempt's authority is judged at its transaction's start, so a completion can
  still win by the few milliseconds its statement takes. Strictness is bounded by statement
  duration. Safety comes from the row lock, not from the clock.

**A lost claim response.** The claim committed, but the worker never learned the attempt id.
Nobody heartbeats that attempt, so its lease expires and the sweep re-queues the job. That costs
one lease duration and one attempt from the budget. The job does not run twice because of this,
since nobody executes the lost attempt.

**Idempotent submission (planned, 2.3).**

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

## Worker (implemented, `worker/`)

A Python process that loops: claim a job, train it, report the result. It runs one job at a time
and talks only to the API.

| Module | Role |
|---|---|
| `config.py` | Parses and re-validates the assignment and config, with the same bounds as the API, and reads settings from the environment |
| `task.py` | Task `synthetic-mlp-v1`: the dataset, model, training, and metrics |
| `client.py` | HTTP calls with timeouts, plus the retry rules below |
| `worker.py` | The loop, idle backoff, and two-stage shutdown |
| `backoff.py` | Exponential backoff with jitter |

### Task `synthetic-mlp-v1`

- **Dataset.** Three interleaved spiral arms in 2-D, 1000 points per class, with angle noise
  σ = 0.2.
  - It is generated from the fixed seed `20260929` and split 2000 train / 1000 validation.
  - Every job sees exactly the same data. Changing any of these constants means a new task id.
- **Model.** An MLP: `hiddenLayers` × (`Linear(hiddenUnits)` + ReLU), then `Linear(3)`. It is trained
  with cross-entropy using SGD or Adam, with `learningRate` and `weightDecay`.
- **Seeds.** The job's `seed` drives weight initialization, via `torch.manual_seed`, and minibatch
  order, via its own `torch.Generator`. The dataset has a separate generator, so the two never
  interact.
- **Metrics.**
  - `valAccuracy`: accuracy of the final model on the validation split. This is the ranking
    metric.
  - `valLoss` and `trainLoss`: mean cross-entropy of the final model on each split.
  - `trainingSeconds`: wall-clock time of the training epochs only.
  - A non-finite loss raises `TrainingDiverged`.
- **Cost.**
  - The example configs train in about 0.1–0.2 s each in a 1-CPU container.
  - A `256x4`, batch-8, 100-epoch config takes about 17 s. That is the "long job" used for
    shutdown and crash demonstrations.
  - The spread in accuracy is real. On the example batch it runs from 0.57 (a tiny model with a low
    learning rate) to 0.98.

### Reproducibility

Training runs on one CPU thread with `torch.use_deterministic_algorithms(True)`.

- **Verified.** Nine jobs with the same config and seed, trained by three different worker
  containers, produced one identical `(valAccuracy, valLoss, trainLoss)` tuple, bit for bit. Only
  `trainingSeconds` varied (0.44–0.51 s).
- **Limits.** The guarantee holds only for the same PyTorch build on the same CPU architecture. The
  macOS arm64 wheel and the Linux aarch64 `+cpu` build gave 0.980 and 0.981 for one example config,
  and matched on the other five. Wall-clock time is never reproducible.

### CPU threads

- `OMP_NUM_THREADS=1` and `MKL_NUM_THREADS=1` are set in the image, before Python starts.
- The worker also calls `torch.set_num_threads(TORCH_NUM_THREADS)` (default 1) and
  `torch.set_num_interop_threads(1)`.
- Each worker container is limited to `cpus: 1.0`.
- Together these mean that N workers use about N cores, instead of N × all-cores threads competing
  with each other. Benchmarks will depend on this.

### Polling and HTTP

| Situation | Behavior |
|---|---|
| `204` (nothing queued) or a failed claim | Wait with backoff, then claim again. Waits double from 0.5 s up to 5 s, each drawn uniformly from [ceiling/2, ceiling] so workers don't poll in lockstep. The backoff resets after a job |
| Timeouts | 3 s to connect and 10 s to read, on every request |
| Claim fails (connection error, timeout, `5xx`) | **Not retried** immediately. A claim is not idempotent: if it committed and only the response was lost, retrying would claim a second job while the first stays `RUNNING` under an attempt nobody holds. The worker just polls again later |
| Completion fails (connection error, timeout, `5xx`) | Retried, up to 5 attempts with jittered backoff from 0.5 s to 8 s. This is safe because the API's guard accepts at most one result per job, and only from the running attempt, so a duplicate can never overwrite anything |
| `409` on completion | Definitive. The result is discarded and the loop continues. `jobState: SUCCEEDED` after a retry usually means the earlier delivery succeeded; 2.3 makes that an explicit replay |
| Heartbeat fails transiently | Sent once. The heartbeat thread tries again at its next interval; two misses in a row still leave the lease alive |
| Failure report fails transiently | Retried like a completion (5 attempts), and safe for the same reason. If it never gets through, the lease expires and recovery records the attempt `EXPIRED` |
| `409` on heartbeat | Definitive: the lease is lost. Training stops at the next minibatch and nothing is reported |
| Other `4xx` | A bug or version skew. Logged and never retried |

### Heartbeats (`heartbeat.py`)

- **A background thread** sends `POST …/heartbeat` every `heartbeatIntervalSeconds`, using the value
  from the claim response. It runs for the whole of training *and* reporting: the lease has to stay
  alive until the result is acknowledged.
- **`lost` is set, and training stops at the next minibatch,** in either of two cases:
  - **The API answers `409`.** The lease expired or the job was reassigned. This is the definitive
    signal, and the server's decision.
  - **No renewal has succeeded for a full lease duration.** This is self-fencing during an API
    outage. The last success was received after the server set that lease's expiry, so once
    `lease_seconds` of local monotonic time have passed since that receipt, the lease has expired
    by the server's clock too. Timing from the request's send time would get this wrong, because
    the server may have renewed later than the send.
  - The self-fencing check only saves wasted training. The server would reject the result anyway.
    It uses elapsed time on one machine and never compares clocks.
- **Verified live with `docker pause`.**
  1. A worker was frozen mid-training for longer than its lease.
  2. Recovery re-queued the job, and the other worker claimed attempt 2.
  3. On unpause, the frozen worker's next heartbeat got `409 ATTEMPT_NOT_CURRENT`. It stopped
     training and reported nothing, and attempt 2's lease was untouched.
  4. Attempt 2's result was accepted.

### Execution and shutdown

- **Training thread.** Training runs in the main thread. `train()` calls `should_stop()` before
  every minibatch, so a stop takes effect within milliseconds. `should_stop()` is true after a
  second stop signal, or once the heartbeat has lost the lease.
- **Why not a training subprocess.** A subprocess could be killed even while stuck inside native
  code. But each job would pay about 1–2 s of PyTorch import, which dwarfs the 0.1 s jobs and would
  distort throughput measurements. Cooperative stopping is enough because we own the training loop.
- **First SIGTERM or SIGINT.** The worker stops claiming. An idle worker exits within 0.2 s, because
  waits are sliced. A busy worker finishes and reports its job first; Compose waits up to
  `stop_grace_period: 60s` before sending SIGKILL.
  - Verified: `docker compose stop worker` with three workers. Two idle workers exited at once. The
    busy one finished its about-17 s job, reported `SUCCEEDED`, and exited with code 0.
- **Second signal.** The current run is aborted at the next minibatch and not reported.
  - Verified live in 1.3: the worker exited 2 s after the first signal, with code 0.
  - Since 2.1, the abandoned attempt's lease expires and recovery retries the job (verified live
    with SIGKILL, which is the harsher version of this).
- **The signal handler only sets flags** and writes to fd 2 with `os.write`. Logging and
  `threading.Event` take locks, and taking a lock inside a handler can deadlock with the code the
  signal interrupted.
- **Why the handler is necessary.** As PID 1 in a container, a process without a SIGTERM handler
  ignores `docker stop` until SIGKILL.
- **Survives an API outage.** Verified: with the API stopped for about 8 s, claims failed at growing
  intervals. When the API came back, the worker resumed and finished a 6-job batch.

**How the worker classifies each outcome** (`worker.py`):

| Outcome | Report | Why |
|---|---|---|
| Training finished and the lease is intact | `complete` | |
| `TrainingDiverged` (a non-finite loss) | `fail`, `TRAINING_DIVERGED`, not retryable | Deterministic for this config and seed: another attempt would diverge the same way. No in-bounds config diverges in practice; this is a safety net |
| The assignment can't be run (unknown task, invalid config) | `fail`, `INVALID_ASSIGNMENT`, not retryable | Every worker of this version would reject it. If even the job and attempt ids are unusable, nothing can be reported, and the lease expires |
| Any other exception during training | `fail`, `WORKER_ERROR`, retryable | Unknown cause, so it is retried within the budget. The worker itself keeps running |
| Aborted by a second stop signal | `fail`, `WORKER_SHUTDOWN`, retryable | Not the job's fault. Reporting hands the job over at once instead of after the lease: verified live, re-queued within 1 s and reclaimed at 4 s |
| Lease lost (`409`, or self-fenced) | nothing | The attempt no longer has authority to report anything |

A shutdown still consumes an attempt from the budget, like any other execution that started.
Giving it back would mean reusing an attempt number, which `attempts_job_attempt_number_unique`
forbids.

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
| Java builds can run in Docker (`scripts/mvnw-docker.sh`) | The dev machine has no JDK. The container uses the same JDK image as the runtime |
| Claim as one CTE + `UPDATE` statement | Selecting, locking, and transitioning happen atomically, with no window between a read and a write |
| The service returns a sealed `CompletionOutcome` instead of throwing | The transaction ends normally in both cases, so later increments can record audit rows on rejection without losing them to a rollback. The controller's exhaustive `switch` maps each outcome to HTTP |
| Rejections share one shape: a `code` plus `jobState` | The MVP had only `ATTEMPT_NOT_CURRENT`; 2.1 carved out `LEASE_EXPIRED`, and 2.3 will add replay and `RESULT_CONFLICT`. Clients that treat any 409 as "stop" stay correct |
| Concurrency tested at the service layer, and the HTTP contract through MockMvc | The race lives in the database. Real threads, each holding its own connection, reproduce it without HTTP-level noise |
| Worker stack: Python 3.14.7 (`python:3.14.7-slim-trixie`), torch 2.14.0, numpy 2.5.3, requests 2.34.2, pytest 9.1.1; uv 0.12.20 in the image with `uv.lock` | These were the latest releases on PyPI and Docker Hub at the time, with cp314 wheels for Linux x86_64/aarch64 and macOS arm64. The lockfile pins every transitive dependency |
| torch from the PyTorch CPU index (`explicit = true`) | PyPI's Linux wheel depends on the CUDA toolkit, cuDNN, and Triton. The CPU build is still 658 MB of the 850 MB environment |
| numpy pinned even though the code never imports it | torch warns at import when NumPy is missing. Pinning it is better than suppressing the warning |
| Synthetic spiral dataset instead of a download | Deterministic, instant to generate, needs a nonlinear model, and gives a wide accuracy spread across hyperparameters |
| One training thread with cooperative cancellation, no subprocess | Heartbeats stay responsive on their own thread, stopping takes milliseconds, and there is no per-job import cost |
| The client never retries claims; it does retry completions | Claims are not idempotent. Completions are made safe to repeat by the API's guard |
| Two-stage shutdown | `docker stop` never throws away finished work, and there is still a way to abort quickly |
| Strict leases | Once `lease_expires_at ≤ now()`, the attempt can do nothing, even before recovery. Its authority window is exactly [claim, last renewal + lease), which is simple to reason about. The cost is that a result arriving a moment late is wasted |
| One recovery mechanism: a periodic sweep in every API instance | Correct under concurrency without leader election. Failing jobs whose final attempt expired doesn't wait for a claim |
| Recovery locks, then updates, in one transaction | It needs the expired attempt ids (which the re-queue erases) and must split rows into re-queued and failed. The row locks keep the data stable between statements |
| Jobs and attempts are updated in one transaction, with row-count checks | A mismatch means the two tables have diverged, and the transaction rolls back instead of committing half a transition |
| `attempts` backfilled by the migration | Recovery marks the current attempt `EXPIRED` and requires exactly one row. Without the backfill, legacy RUNNING jobs would make every sweep fail |
| Heartbeats continue through reporting | A slow, retried report would otherwise let the lease lapse after training succeeded |
| The worker self-fences after a full lease without a successful renewal | Saves compute during outages. It is measured from the last success's receipt, on the local monotonic clock |
| Tests turn off the periodic sweep (`scheduler.recovery.enabled=false`) | A background sweep would race tests that expire a lease on purpose. The tests call `RecoveryService` directly |
| A failure report is one guarded `UPDATE` with `CASE`, not a read followed by a write | The budget and authority are checked under the lock that performs the write |
| `retryable` comes from the worker; the budget comes from the server | Only the worker knows whether an error is deterministic. Only the server can enforce the limit across workers |
| Every started execution counts against `maxAttempts`, including shutdowns | A simple, uniform rule, and attempt numbers are never reused |
| Attempt history at `GET /jobs/{id}/attempts`, plus `lastError` on job responses | Details on demand, while list views still show why a job failed without one request per job. Attempt ids are never exposed |
