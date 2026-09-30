# Progress

_Last updated: 2026-09-30_ (3.3 done; CI awaiting its first GitHub run)

## Status

| Increment | Scope | Status |
|---|---|---|
| 1.1–1.3 | MVP: submission API, atomic claims, fenced completion, Python worker, Compose | done |
| 2.1 | Leases, heartbeats, recovery sweep, strict fencing, attempt history (V2) | done |
| 2.2 | Failure reports (retryable or not), bounded retries on reported failures, attempt history in the API | done |
| 2.3 | Idempotent submission, safe repeated completion, best-configurations endpoint | done |
| 2.4 | Scripted end-to-end demo with checks (`scripts/demo.sh`) | done |
| 3.1 | CI (GitHub Actions): API tests, worker tests, and the checked demo on every push | built and replayed locally; not yet run on GitHub |
| 3.2 | Structured logs: JSON lines with shared ids and event names from the API and workers | done |
| 3.3 | Benchmarks: dispatch, submission, and training throughput, with correctness checks (`scripts/benchmark.sh`) | done |
| 3.4 | Architecture README | next |

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

Committed on branch `milestone-2`: 1.2–2.1 as `84cf4a3`, 2.2 as `d0d7daf`, 2.3 as `eefcd0e`, 2.4 as
`b62e554`, 3.1 as `ba836a9`, and 3.2 as `3033c08`.

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

**2.3**

- **`V4__idempotency_keys.sql`:** `experiments.idempotency_key` (UNIQUE) and
  `request_fingerprint`.
- **`POST /experiments` accepts `Idempotency-Key`:**
  - the key is stored with a `RequestFingerprint`, a SHA-256 over the canonical form of the
    validated request;
  - it is inserted with `INSERT … ON CONFLICT DO NOTHING` in the creating transaction;
  - responses: `201` for a new key, `200` plus `Idempotent-Replayed` for an identical request,
    `409 IDEMPOTENCY_KEY_REUSED` for a different one.
- **Completion replay:** an identical repeat from the accepted attempt returns `200` with
  `replayed: true` and changes nothing. A different payload returns `409 RESULT_CONFLICT`. The
  worker logs "acknowledged on retry".
- **`GET /experiments/{id}/best?limit=N`:** successful jobs by `valAccuracy`, ties broken by
  `jobIndex`. Parameter errors are reported as field errors.

**2.4**

- **`scripts/demo.sh`** covers the whole story in about 100 s, in an isolated Compose project
  (`mlsched-demo`, API on `:18080`) with 10 s leases:
  1. **`sweep`:** a 200-configuration sweep across 3 workers, an idempotent resubmission, and the
     ranking;
  2. **`crash`:** SIGKILL a worker mid-training, then recovery and attempt 2;
  3. **`stale`:** `docker pause` a worker past its lease. Its old attempt reports while the
     replacement runs and again after it succeeds; the worker is woken and fenced.
- 17 `[ok]`/`[FAIL]` checks (1 at startup, then 5 / 4 / 7 per scenario); the exit status is
  non-zero on any violation. `KEEP=1` leaves the stack up, and scenarios can be picked by name.
- **`RecoverySweeper`** now schedules itself from `SchedulerProperties`, so all lease policy has
  one binding path. It logs the effective policy at startup.

**3.1**

- **`.github/workflows/ci.yml`** runs three parallel jobs on `ubuntu-24.04` for every push, and on
  demand:
  - **`api`:** `./mvnw -B -ntp verify` on the image's exact Temurin build. setup-java gets
    `21.0.12+101.0.LTS` with `verify-signature: true`, and a step checks `$JAVA_HOME/release`. The
    Maven cache is keyed on the POM and the wrapper properties.
  - **`worker`:** setup-uv installs uv 0.12.20, with its checksum pinned, and sets
    `UV_PYTHON=3.14.7`. Then `uv sync --locked`, which downloads CPython 3.14.7, and
    `uv run --no-sync pytest`.
  - **`e2e`:** builds both images, then runs `scripts/demo.sh` and puts its output in the job
    summary. Logs are uploaded on failure.
  - The actions are pinned by SHA. The token is limited to `contents: read` and not persisted.
  - Off `main`, a newer push cancels a branch's older run. Each run on `main` has its own group.
  - The long steps time out before their job does, so a hang still uploads logs.
- **Dockerfile frontend pinned** to `docker/dockerfile:1.26.0`; it was the moving `:1`.
- **`scripts/demo.sh`:**
  - It prints the end of `compose up`'s output when the stack fails to start. A build error never
    reaches the containers' logs.
  - The sweep's hang guard went from 180 s to 300 s, and the crash scenario's from 90 s to 120 s.
    A 2-CPU x86 runner was estimated at roughly 45–145 s for the sweep, leaving too little
    headroom.
- **`CLAUDE.md`:** the CI commands, and the rules for keeping versions in sync with the images.

**3.2**

- **API:** every log call adds `event.action` and its ids as SLF4J key-value pairs.
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` in `compose.yaml` turns them into Spring Boot's ECS JSON.
  A new `experiment.created` event logs each submission.
- **Worker:** `scheduler_worker/logs.py` is a stdlib JSON formatter with the same shape, switched on
  by `LOG_FORMAT=json`.
  - `AttemptLog` prefixes each line with `job=N attempt=M` and attaches the attempt's ids to it.
  - Every line carries `service.name` and `workerId`.
- **`scripts/demo.sh`** reads events instead of grepping text, in four places: the policy, the stale
  attempt's id, the fencing, and the "never reported" check. That check now also requires the same
  query to find attempt 2's result.
  - The stale scenario ends by printing the job's timeline, built from the JSON logs of the API and
    all workers.
- **Docs:** the field and event catalog, and query recipes, are in DESIGN.md ("Logs") and the
  README. CLAUDE.md now treats logs as an interface.

**3.3**

- **`scripts/benchmark.sh`** takes about 10 minutes, in its own project (`mlsched-bench`,
  `:19080`). It measures:
  - dispatch without training, with 1 to 16 fake workers;
  - submission of 1, 100, and 500 jobs;
  - HTTP without the database;
  - the disk's flush rate (`pg_test_fsync`);
  - training with 1, 2, and 4 real workers.
- **`scripts/bench/bench.py`** is the client: standard library only, inside the Compose network,
  one process per fake worker.
- **`scripts/bench/report.py`** writes each run's JSON and Markdown under `docs/benchmarks/`. It
  regenerates the results and experiments sections of `docs/BENCHMARKS.md`, so no number there is
  typed by hand.
- **Correctness is checked while measuring:**
  - every dispatch run must claim and complete each job exactly once;
  - every training run must succeed on each job's first attempt, with every worker taking part;
  - a run that fails a check exits `1`.
- **The environment is recorded, not assumed:**
  - the host, its power source, and the load of other containers;
  - Docker, the commit, and whether `api/`, `worker/`, or `compose.yaml` had changed;
  - PostgreSQL's durability settings;
  - the CPU limits and torch threads that were in effect, and the API's database connections.
- **Experiment knobs:** `BENCH_POOL_SIZE` and `BENCH_API_LOG_LEVEL`, which default to the stack's
  own values.

## Verified (2026-09-29: macOS arm64, Docker Desktop 29.2.0)

**3.3 (2026-09-30; the runs are in `docs/benchmarks/`):**

- **Main run `20260930T161444Z`:** all parts, 3 rounds, 7.4 minutes. It passed every check:
  - all 15 dispatch runs claimed and completed each of their 5,500 jobs exactly once;
  - all 9 training runs succeeded on every job's first attempt, with every worker taking part.

  Medians, all on battery power:

  | Measurement | Result |
  |---|---|
  | Dispatch | 578 jobs/s with 1 fake worker and 2,967 with 16 (claim p50 0.77 and 2.56 ms) |
  | Submitting 500 jobs | p50 31.7 ms |
  | HTTP without the database | p50 0.16 ms |
  | One flush | 95 µs |
  | Training | 5.4, 11.2, and 20.6 jobs/s with 1, 2, and 4 workers, with 95–96% of worker time inside the training loop |
- **Experiments:** four `api` runs: the defaults twice, a pool of 20, and log level WARN.
  - Per-job logging costs about a third of dispatch throughput.
  - The pool is not a limit up to 8 fake workers.
  - 16 fake workers is too noisy to judge the pool. `docs/BENCHMARKS.md` has the tables and the
    reasoning.
- **The tooling itself:**
  - A one-round smoke run exercised every part end to end before the real runs.
  - In calibration, a single client process capped throughput at 16 fake workers. Running one
    process per fake worker removed that; the client now uses about 1 core in total.
  - A stub server with invalid response bodies, and a closed port, each made the client report
    errors instead of hanging.
  - `docker compose run` forwards stdin like `exec` does, and it swallowed the rest of a heredoc
    during probing. The script gives it `</dev/null`.
  - shellcheck reports no warnings for `scripts/benchmark.sh`.

**3.2:**

- **API:** `scripts/mvnw-docker.sh verify` ran 149 tests, 0 failures. `StructuredLogTests` (7) is new:
  it renders events captured from real calls through Boot's ECS encoder, and checks their fields and
  types.
- **Worker:** `uv run pytest` ran 69 tests, 0 failures. There are new tests of the JSON lines, of
  `AttemptLog`, of `LOG_FORMAT`, and of the events of a run and of a lost lease.
- **Mutation checks, each caught:**
  - `job.claimed` without `attemptId`;
  - the rejection `code` logged as the job state;
  - `AttemptLog` dropping per-call fields;
  - dotted names not nested;
  - `lease.lost` without its `event.action`.
- **Demo:** `scripts/demo.sh` passed 17/17. The printed timeline for the stale job showed, in order:
  1. claim, then training;
  2. `lease.expired`, then attempt 2's claim 0.12 s later;
  3. the stale `result.rejected`, the frozen worker's `heartbeat.rejected`, and its `lease.lost`;
  4. attempt 2's `result.accepted`, from both sides;
  5. the late `result.rejected`.
- **Negative check:** with the workers switched back to `LOG_FORMAT=text`, `scripts/demo.sh stale`
  exited 1. Both worker-log checks failed, including the "never reported" check, which would
  previously have passed.
- **Live:** the README's recipes were run on a scratch stack. With PostgreSQL stopped under the
  API, a request got `500`, and `request.failed` carried `error.type`
  (`CannotGetJdbcConnectionException`) and the stack trace.
- **Not re-run for 3.2:** the CI replays. The CI jobs' commands did not change.

**3.1, replayed locally; not yet run on GitHub.** Each job's `run:` steps were extracted from
`ci.yml` and run with `bash --noprofile --norc -eo pipefail`, as `shell: bash` does on the runner.

- **`e2e`:**
  - Every demo container was confined to 2 CPUs (`cpuset: "0,1"`, confirmed with `docker
    inspect`): 17/17 checks passed, the demo took 89 s, and the sweep took 25 s.
  - The same with amd64 images under Rosetta: 17/17 checks passed; the demo took 160 s and the
    sweep 45 s, against the old 180 s limit.
  - The final state, unconfined, against the repo itself: 17/17 in 64 s. The summary step wrote
    the fenced demo output to the local file standing in for `$GITHUB_STEP_SUMMARY`. The
    log-collection step, forced to run, captured `ps -a` plus every container's log.
- **Failure paths:**
  - `scripts/demo.sh bogus | tee` exits 2 under pipefail, and 0 without it.
  - When the stack could not start, compose's reason was printed. The summary step and the log
    collection still ran, although there were no containers to take logs from.
  - The summary step is a no-op when there is no log.
- **`api`:** 142 tests, 0 failures at `--cpus=2`, including a clean compile.
  - `Check the JDK build` passes on the image's JDK.
  - setup-java's own bundle was run in an amd64 container with the CI's `java-version` and
    `verify-signature`, but without the cache service. It resolved `21.0.12+101.0.LTS` to the
    `jdk-21.0.12.1+1` tarball, whose sha256 matches the image's.
  - The signature verified with gpg present. Without gpg the step failed, where the default only
    warns.
- **`worker`:** on amd64 Ubuntu 24.04 with uv 0.12.20 and `UV_PYTHON=3.14.7`, 62 passed, including
  5 runs at `--cpus=2`. `uv sync --locked` refused a changed pin.
- **Pins:**
  - All four action SHAs equal their release tags, and each is the latest stable release.
  - The uv checksum matches the release's `.sha256` file and the downloaded tarball.
  - `docker/dockerfile:1` and `:1.26.0` have the same digest.
- **Linters:** actionlint 1.7.12 with shellcheck reports 0 errors. zizmor 1.30.1 (default,
  pedantic, and auditor personas) has no findings; its positive control found 7 on a deliberately
  bad copy.
- **Differences from a real runner:**
  - The `e2e` replays used Docker 29.2 with Compose v5 and macOS's bash 3.2.
  - The ubuntu-24.04 image's published software list gives Docker 28.0.4, Compose 2.38.2, and bash
    5.2. None of these has been observed on a run yet.
  - The build and the demo's own shell were not CPU-confined, and the builds were warm.

**2.4, the demo itself (verified against real containers):**

- **Full run:** every check passed.
  - Sweep: 200/200 in about 18 s, split 66 / 69 / 65, with a peak of 3 running.
  - Crash: SIGKILL at 42 s, `QUEUED` at 50 s, attempt 2 at 51 s, `SUCCEEDED` at 67 s.
  - Stale: attempt 2 at 81 s. The stale report got `409` while attempt 2 was running, and the
    woken worker was fenced in under a second. The late report got `409`, and the result was
    unchanged.
- **Negative check:** against an API with the completion guard's attempt check removed, the demo
  exited `1`.
  - The stale report got `500`, from `IllegalStateException: Expected 1 running attempt row(s) …
    but updated 0`.
  - The attempts row check rolled the write back, so the job kept attempt 2's result (0.992).
- **After restoring:** `scripts/demo.sh crash stale` exited `0`, with 12 of 12 checks passing and
  no containers left behind.

**2.3, API: `scripts/mvnw-docker.sh verify` runs 142 tests, 0 failures.**

- New tests:
  - a sequential replay; identity by meaning (key order, number spelling, an omitted default);
  - key reuse with a changed body, and with the job order changed;
  - malformed keys;
  - 8 concurrent submissions with one key, and with one key but two bodies;
  - the fingerprint unit tests, plus a golden pin;
  - completion replay versus conflict, versus a stale attempt with the same payload, and versus a
    late first report;
  - 8 simultaneous identical deliveries (1 accepted, 7 replayed);
  - ranking, limits, and 404.
- Six mutation checks, each caught:
  - no `ON CONFLICT` (duplicates become 500s);
  - fingerprints not compared;
  - an unsorted, raw canonical form (caught only by the golden test, as intended);
  - replay without comparing results;
  - never replaying;
  - ranking in ascending order.
- The first test run caught a real bug. `ExperimentService.create` called the `@Transactional`
  `submit` on `this`, which bypasses the proxy. `MANDATORY` propagation refused the writes; fixed
  and documented in CLAUDE.md.

**2.3, worker: `uv run pytest` runs 62 tests, 0 failures** (the client reports `replayed`).

**2.3, live (fresh stack; V1–V4 applied):**

- Same key and body: `201`, then `200` with `Idempotent-Replayed`. A changed body gets `409`.
- 10 parallel curls with one new key: exactly one `201` and nine `200`s, giving one experiment with
  6 jobs. The losing inserts used up identity values, so ids are not gapless.
- Lost-ack simulation: `replayed: true`, and `finished_at` unchanged. A different payload gets
  `409 RESULT_CONFLICT`.
- `best` on the 100-job grid: the top five match the 1.3 run; ties at 0.995 are ordered by
  `jobIndex`.

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
- **A submission without an `Idempotency-Key` is not deduplicated.** Keys are global in scope and
  never expire.
- **Strictness is bounded by statement duration.** A lease is judged at its transaction's start,
  so a completion can win by the milliseconds its statement takes (see DESIGN.md).
- **CI has not run on GitHub yet.** Its jobs were replayed locally under CI-like limits (see
  Verified), but the runner's Docker and Compose versions differ from Docker Desktop's.
- **Not everything in CI is pinned by content:**
  - images are pinned by version tag, not digest;
  - Maven and its dependencies are trusted over TLS (see DESIGN.md, Continuous integration).
- **Per-job INFO logs cost about a third of dispatch throughput** at the benchmark's rates. Logging
  after commit is the likely fix (see `docs/BENCHMARKS.md`).
- **Logs stay in Docker.** Nothing ships, retains, or indexes them. `docker compose logs` plus
  `jq` is the query tool, and removing a container removes its logs.
- **Other:** bodies parsed before size limits, no local JDK.

## Next: the rest of milestone 3 (evidence and polish)

1. **The first CI run:** push `milestone-2`, then check the three jobs and the demo's summary in the
   Actions tab. Runner timings go here as observations, not benchmarks.
2. **3.4 Architecture README:** the one-page architecture, how to run the demo, and what each
   guarantee rests on.
3. **Optional: log events after commit.** The benchmark measured their cost. The change would take
   the log write out of the transaction, and drop DESIGN.md's rolled-back-event caveat. Then re-run
   `scripts/benchmark.sh api` and compare.
