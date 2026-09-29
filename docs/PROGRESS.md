# Progress

_Last updated: 2026-09-29_

## Status

| Increment | Scope | Status |
|---|---|---|
| 1.1–1.3 | MVP: submission API, atomic claims, fenced completion, Python worker, Compose | done |
| 2.1 | Leases, heartbeats, recovery sweep, strict fencing, attempt history (V2) | done |
| 2.2 | Failure reports (retryable or not), bounded retries on reported failures, attempt history in the API | next |
| 2.3 | Idempotent submission, safe repeated completion, best-configurations endpoint | planned |
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

## Verified (2026-09-29: macOS arm64, Docker Desktop 29.2.0)

**API: `scripts/mvnw-docker.sh verify` runs 86 tests, 0 failures.** New in 2.1:

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

- **Failures can't be reported yet (2.2).** Divergence, an unrunnable assignment, an aborted run,
  and an undeliverable result all rely on lease expiry. The job is retried until `maxAttempts`,
  then `FAILED`. That is bounded, but deterministic failures waste attempts, and each costs about
  35 s.
- **Attempt history is in the database, not yet in the API.** 2.2 adds it to `GET /jobs/{id}`.
- **Duplicate submissions create duplicate experiments,** and a retried completion gets `409`
  rather than a replay (2.3).
- **Strictness is bounded by statement duration.** A lease is judged at its transaction's start,
  so a completion can win by the milliseconds its statement takes (see DESIGN.md).
- **No best-configurations endpoint** (2.3); rank with `jq`.
- **Other:** no CI, bodies parsed before size limits, no local JDK.

## Next: increment 2.2 (failures and bounded retries)

1. `POST /worker/jobs/{id}/fail` with `{attemptId, retryable, errorType, message}`.
   - It uses the same guards as completion (state, attempt, live lease).
   - A retryable failure with attempts left goes back to the queue. Otherwise the job is `FAILED`.
   - The attempt is recorded `FAILED` with its error.
2. `GET /jobs/{id}` includes `attempts: [...]` (number, worker, status, times, error) and the
   latest error.
3. The worker reports divergence (non-retryable), unrunnable assignments (non-retryable), and
   unexpected exceptions (retryable), instead of waiting for the lease to run out.
4. Tests:
   - retries stop at `maxAttempts` through reported failures, and through a mix of failures and
     expiries;
   - a non-retryable failure ends the job at once;
   - a stale attempt cannot fail a newer attempt;
   - a failure racing recovery.
