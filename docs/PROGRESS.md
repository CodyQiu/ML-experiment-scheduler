# Progress

_Last updated: 2026-09-29_

## Status

| Increment | Scope | Status |
|---|---|---|
| 1.1–1.3 | MVP: submission API, atomic claims, fenced completion, Python worker, Compose | done |
| 2.1 | Leases, heartbeats, recovery sweep, strict fencing, attempt history (V2) | done |
| 2.2 | Failure reports (retryable or not), bounded retries on reported failures, attempt history in the API | done |
| 2.3 | Idempotent submission, safe repeated completion, best-configurations endpoint | next |
| 2.4 | Scripted kill-a-worker and stale-worker demos | planned |
| M3 | CI, structured logs, architecture README, recovery demo write-up, benchmarks | planned |

## Completed

**1.1–1.3 (milestone 1)**

- Spring Boot 4.1.1 API on Java 21, PostgreSQL 18, and Flyway V1.
- Strict validated submission, and a single-statement `SKIP LOCKED` claim.
- A guarded completion, decided by the affected-row count.
- A Python 3.14 worker (torch 2.14 CPU): deterministic task `synthetic-mlp-v1`, bounded retries of
  completions, two-stage shutdown.
- Compose, with scalable workers.

**2.1**

- **`V2__leases_and_attempts.sql`:**
  - `jobs.lease_expires_at`, present exactly when the job is `RUNNING`.
  - An `attempts` table with one-running and one-success partial unique indexes.
  - A backfill that makes V1's stuck `RUNNING` jobs recoverable.
- **The claim** sets the lease and inserts the attempt row in one transaction. The assignment
  carries `leaseSeconds` and `heartbeatIntervalSeconds`.
- **`POST /worker/jobs/{id}/heartbeat`**, guarded on state, attempt, and `lease_expires_at > now()`.
- **Completion** gained the same strict lease check, and a `409 LEASE_EXPIRED` distinct from
  `ATTEMPT_NOT_CURRENT`.
- **`RecoveryService` plus `RecoverySweeper`:**
  - Every 5 s, in batches of 100, lock expired leases with `SKIP LOCKED`.
  - Re-queue a job while attempts remain, otherwise `FAILED`, and mark the attempts `EXPIRED`.
- **`SchedulerProperties`:** lease 30 s, heartbeat 10 s (at most half the lease, enforced at
  startup), sweep every 5 s. Overridable with `SCHEDULER_*` environment variables.
- **Worker:** a heartbeat thread for training and reporting. A `409` or a full lease without a
  successful renewal stops training at the next minibatch.
- **`CLAUDE.md`:** commands, architecture rules, testing conventions, and environment gotchas.

Committed as `84cf4a3` on branch `milestone-2`, covering 1.2 through 2.1.

**2.2**

- **`V3__reported_failures.sql`:**
  - `attempts.retryable`.
  - Every `FAILED`/`EXPIRED` attempt must carry an `error_type`, and every `FAILED` attempt a
    `retryable` flag.
  - A backfill for V1-era rows.
- **`POST /worker/jobs/{id}/fail`:** one guarded `UPDATE` that re-queues when the failure is
  retryable and attempts remain, and otherwise fails the job. The attempt records the error.
- **`GET /jobs/{id}/attempts`** returns the history without attempt ids. `lastError` appears on
  every job response, via a `LATERAL` join.
- **Worker failure reports:**
  - non-retryable: `TRAINING_DIVERGED`, `INVALID_ASSIGNMENT`;
  - retryable: `WORKER_ERROR` (the worker survives the exception) and `WORKER_SHUTDOWN` (an
    immediate handover on a second stop signal).

## Verified (2026-09-29: macOS arm64, Docker Desktop 29.2.0)

**2.2, API: `scripts/mvnw-docker.sh verify` runs 109 tests, 0 failures.**

- New tests:
  - retries stop at `maxAttempts` through failures alone, and through a mix of expiries and
    failures;
  - a non-retryable failure ends the job at once;
  - a stale attempt cannot fail its successor;
  - an expired lease cannot report a failure;
  - a failure cannot undo a success.
- The HTTP contract, the attempt history, and `lastError` are covered.
- The race tests are generalized (`ReportRecoveryRaceTests`): completion and failure, each in both
  lock orders.
- V3 schema and migration tests.
- Mutation checks. The fail guard's lease and attempt checks, its budget condition, and its
  retryable condition are each caught. Ignoring the budget was stopped by V1's
  `jobs_queued_has_attempts_left` CHECK.

**2.2, worker: `uv run pytest` runs 61 tests, 0 failures.**

- Covers the classification of each outcome, identity parsing, and `client.fail` with retries and
  truncation.
- Mutations are caught: divergence marked retryable, an unreported shutdown, and unexpected errors
  escaping the loop.

**2.2, live (fresh stack; V1–V3 applied):**

- Two SIGTERMs to the worker training a job:
  - `WORKER_SHUTDOWN` reported, and the job was re-queued within 1 s instead of after the 30 s
    lease;
  - the other worker claimed attempt 2 at 4 s, and it `SUCCEEDED` at 31 s;
  - the history and `lastError` explain both attempts.
- A manual non-retryable report (`TRAINING_DIVERGED`) ended its job `FAILED` after 1 of 3 attempts.
  A duplicate report got `409`, and progress showed `failed: 1`.

**2.1, API: 86 tests at the time.** New in 2.1:

- Heartbeat and strict-lease HTTP tests. An expired lease can neither renew nor complete before
  recovery runs, and a stale attempt cannot touch its successor's lease.
- Recovery tests:
  - an expired lease is re-queued, and attempt 2 follows;
  - live, finished, and queued jobs are untouched;
  - the final attempt expiring makes the job `FAILED`;
  - 8 concurrent sweeps recover each of 40 attempts exactly once.
- `CompletionRecoveryRaceTests` forces both orders of the completion-versus-recovery race with held
  transactions.
- `MigrationTests` runs V1 with data, then V2.
- Mutation checks. Each removed guard fails the tests aimed at it:
  - the lease check on completion;
  - the lease check on heartbeat;
  - the attempt check on heartbeat;
  - the sweep's row lock (concurrent sweeps collide, and the completion-first race breaks);
  - the sweep's expiry predicate.

**Worker: `uv run pytest` runs 55 tests, 0 failures.**

- New tests cover the `Heartbeat` thread (renewal, `409`, tolerated transient failures,
  self-fencing on a fake clock), and training that stops when the lease is lost.
- Mutations of the `409` handling, of self-fencing, and of the lease check in training are all
  caught.
- One weak test was found by mutation and tightened.

**Live Compose stack (fresh database, 2 workers, default settings):**

- V1 and V2 applied. The example batch left 6 jobs `SUCCEEDED` and 6 attempts `SUCCEEDED`.
- **SIGKILL** of the worker training a 17 s job, before its first heartbeat:
  - re-queued 30 s after the kill (lease plus sweep),
  - claimed by the other worker 1 s later as attempt 2, which `SUCCEEDED`;
  - attempt 1 is `EXPIRED` with `LEASE_EXPIRED`.
- **`docker pause`** of the worker training a job, for longer than its lease:
  - re-queued at 31 s, attempt 2 claimed by the other worker at 32 s;
  - on unpause, the frozen worker's heartbeat got `409 ATTEMPT_NOT_CURRENT`, and it stopped
    training and reported nothing;
  - attempt 2's result was accepted.

## Known limitations (current)

- **A result or failure report that can't be delivered after its retries** still relies on lease
  expiry. The attempt is recorded `EXPIRED`, not with the worker's error.
- **Duplicate failure reports get `409`.** That's harmless: the first one was applied.
- **Duplicate submissions create duplicate experiments,** and a retried completion gets `409`
  rather than a replay (2.3).
- **Strictness is bounded by statement duration.** A lease is judged at its transaction's start,
  so a completion can win by the milliseconds its statement takes (see DESIGN.md).
- **No best-configurations endpoint** (2.3); rank with `jq`.
- **Other:** no CI, bodies parsed before size limits, no local JDK.

## Next: increment 2.3 (idempotency and ranking)

1. **`Idempotency-Key` on `POST /experiments`:**
   - a canonical fingerprint of the validated request, computed from parsed values with defaults
     applied;
   - a unique key column, and `INSERT … ON CONFLICT DO NOTHING` in the submission transaction;
   - the same key with the same body returns `200` with the original experiment; with a different
     body, `409 IDEMPOTENCY_KEY_REUSED`.
2. **Safe repeated completion.** An identical retry from the attempt that succeeded returns `200`
   with `"replayed": true` and changes nothing. A different payload returns `409 RESULT_CONFLICT`.
   A late first report stays `LEASE_EXPIRED`.
3. **`GET /experiments/{id}/best?limit=N`:** successful jobs by `valAccuracy`, descending, with a
   deterministic tie-break.
4. **Tests:** concurrent duplicate submissions create one experiment; key reuse with a different
   payload; JSON key order and number spelling don't change identity; a lost acknowledgement
   followed by a retry.
