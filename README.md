# ML Experiment Scheduler

Run hyperparameter sweeps on a pool of workers, and get the best configurations back. The
scheduler stays correct when workers crash, freeze, or report twice.

- You submit a batch of training configurations.
- Workers claim them, train a small PyTorch model on CPU, and report metrics.
- PostgreSQL keeps track of everything.

## Quick start

You need Docker (running) and Python 3.9 or later, which macOS and most Linux systems already have.

```bash
./sched start      # start the scheduler: PostgreSQL, the API, and 2 workers
./sched sweep      # submit a sweep: 100 training configurations
./sched watch      # follow it live; it shows the best results when it finishes
./sched stop       # stop everything (your data is kept)
```

The first `start` builds the images, which takes a few minutes. After that it takes seconds.

```
$ ./sched best -n 3
RANK  VAL ACCURACY  CONFIG                                  SEED  JOB
1     0.995         adam lr=0.03 128x1 batch=64 epochs=20   0     77
2     0.995         adam lr=0.1 64x1 batch=64 epochs=20     0     93
3     0.993         adam lr=0.003 128x2 batch=64 epochs=20  0     39
```

## See it survive failures

```bash
./sched demo       # about 100 s; also needs jq (brew install jq)
```

The demo runs a separate copy of the scheduler with short leases, so it never touches your data.
It then breaks things on purpose:

1. it shares a 200-configuration sweep among 3 workers;
2. it kills a worker in the middle of training, and another worker finishes the job;
3. it freezes a worker past its deadline and lets it try to report. Its stale result is rejected,
   the worker is stopped when it wakes, and the job's whole story is printed from the logs.

Every claim is checked, and the demo exits with an error if any check fails.

## Commands

| Command | What it does |
|---|---|
| `./sched start [--workers N]` | Starts PostgreSQL, the API, and N workers (default 2) |
| `./sched workers N` | Changes how many workers run |
| `./sched sweep [--seeds N]` | Submits the built-in grid: 100 configurations, each run with N seeds |
| `./sched submit FILE` | Submits your own batch from a JSON file; see [`examples/small-batch.json`](examples/small-batch.json) |
| `./sched list` | Shows recent experiments and their progress |
| `./sched status [ID]` | Shows an experiment's progress |
| `./sched watch [ID]` | Follows an experiment until it finishes, then shows the best results |
| `./sched best [ID] [-n N]` | Shows the best configurations |
| `./sched jobs [ID] [--failed]` | Shows every job of an experiment, or only the ones that failed |
| `./sched job JOB_ID` | Shows one job and every attempt to run it |
| `./sched logs [-f] [--job ID]` | Shows readable logs from the API and the workers |
| `./sched stop [--delete-data]` | Stops everything. The data is kept unless you ask |
| `./sched test` | Runs the test suites (needs only Docker) |
| `./sched bench` | Measures throughput and latency, in about 7 minutes |

`ID` defaults to the latest experiment, and `./sched COMMAND -h` shows a command's options.

## How it works

```
 you: ./sched, or any HTTP client               worker × N (Python 3.14, PyTorch on CPU)
        │ submit, check progress                       │ claim → heartbeat → report
        ▼                                              ▼
 ┌──────────────────────────────────────────────────────────────────┐
 │ API: Java 21, Spring Boot 4.1                                    │
 │ validation · guarded state transitions · leases · recovery sweep │
 └────────────────────────────────┬─────────────────────────────────┘
                                  │ JDBC: explicit SQL, short transactions
                                  ▼
                   PostgreSQL 18: experiments, jobs, attempts
```

- **The API is the database's only client.** Workers change state only through HTTP, and the API
  turns each request into one guarded SQL statement.
- **There is no message broker.** Workers poll for work, and row locks with
  `FOR UPDATE SKIP LOCKED` make the `jobs` table a correct work queue.
- **You submit configurations, not code.** Each job is a set of hyperparameters for one fixed task,
  never a command.
- **Every run of a job is an attempt with its own id.** Only the job's current attempt can change
  it, so a stale worker can't overwrite anything.

A job's life:

```
         claim: the lease starts            complete: current attempt, live lease
 QUEUED ─────────────────────────▶ RUNNING ────────────────────────────────────▶ SUCCEEDED
   ▲                                 │  │
   │  lease expired, or a retryable  │  │  lease expired on the final attempt, a
   └── failure, with attempts left ──┘  │  non-retryable failure, or a retryable one
                                        │  on the final attempt
                                        └──────────────────────────────────────▶ FAILED
```

- **A claim is one SQL statement.** It locks the oldest queued job, marks it running under a new
  attempt id, and starts a 30-second lease on the database's clock.
- **While training, a worker renews its lease every 10 seconds.** If a renewal is refused, or none
  succeeds for a whole lease, it stops at the next minibatch.
- **Every change is one `UPDATE` guarded by the job's state, its attempt id, and a live lease.**
  The number of rows it updated decides whether the change happened. A refused request gets a
  `409` with a reason.
- **The API sweeps for expired leases every 5 seconds.** An expired job goes back to the queue, or
  fails once its attempts are used up.
- **`SUCCEEDED` and `FAILED` are final.** No update applies to them, so an accepted result is
  never replaced.

## What it guarantees, and what each guarantee rests on

| Guarantee | Rests on | Shown by |
|---|---|---|
| Two workers never both claim a job | The single-statement claim with `FOR UPDATE SKIP LOCKED`, and a unique index allowing one running attempt per job | `WorkerConcurrencyTests`; every benchmark run, with up to 16 concurrent claimers, checks it |
| Only the current attempt can renew, complete, or fail its job | Guarded `UPDATE`s decided by the affected-row count. Leases are strict: an expired attempt loses its authority before the sweep even runs | `LeaseApiTests`, `FailureTests`; the demo's frozen-worker scenario |
| A crashed or frozen worker's job runs again | Leases renewed by heartbeats, and a sweep that locks expired rows with `SKIP LOCKED`, so several API instances can sweep at once | `RecoveryTests`, including 8 concurrent sweeps; the demo's crash and frozen-worker scenarios |
| When a report races recovery, exactly one of them wins | Both lock the same row. A report that waits behind recovery fails its guard when PostgreSQL re-checks it after the lock wait. Recovery skips a row a report holds (`SKIP LOCKED`) | `ReportRecoveryRaceTests`, which forces both orders |
| Retries stop at `maxAttempts` | Every started attempt counts. The failure guard and the sweep both check the budget, and a CHECK constraint backs them | `FailureTests` |
| A job's result is accepted at most once, and never replaced | Final states match no guard, and a unique index allows one successful attempt per job | `CompletionReplayTests`, `WorkerConcurrencyTests`, `JobSchemaInvariantTests` |
| Retrying a submission or a report is safe | For submissions: a unique `Idempotency-Key`, `INSERT … ON CONFLICT DO NOTHING`, and a fingerprint of the request. For reports: an identical repeat is acknowledged as a replay, and anything else from that attempt gets `409` | `IdempotencyTests`, including 8 concurrent submissions; `CompletionReplayTests` |
| A submission is all or nothing, and invalid input stores nothing | Validation finishes before the transaction starts. The experiment and its jobs share one transaction, which the repositories require (`Propagation.MANDATORY`) | `ExperimentServiceTests`, `ExperimentApiTests` |
| Stored jobs never break the lifecycle's rules | CHECK constraints in the schema | `JobSchemaInvariantTests` |

**Jobs run at least once, not exactly once.** A job can run more than once, for example after a
crash, or when a worker that is still alive loses its lease. The scheduler accepts only one result
per job. [docs/DESIGN.md](docs/DESIGN.md) has the full list, with the SQL and its reasoning.

## The REST API

`./sched` is a thin client of this API; any HTTP client works.

| Endpoint | Used by | Does |
|---|---|---|
| `POST /experiments` | you | Submits up to 500 jobs, all or nothing. An `Idempotency-Key` header makes a retry safe |
| `GET /experiments` | you | Lists the newest experiments first, with their progress |
| `GET /experiments/{id}`, `…/jobs`, `…/best` | you | Returns progress, every job with its latest error, or the best results by `valAccuracy` |
| `GET /jobs/{id}`, `GET /jobs/{id}/attempts` | you | Returns one job, or its attempt history |
| `POST /worker/jobs/claim` | workers | Returns the oldest queued job with a 30-second lease, or `204` if none is queued |
| `POST /worker/jobs/{id}/heartbeat` | workers | Renews the lease |
| `POST /worker/jobs/{id}/complete`, `…/fail` | workers | Reports metrics, or a failure marked retryable or not |

```bash
curl -s -X POST localhost:8080/experiments -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-batch-1' --data @examples/small-batch.json
```

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details, with a field-by-field
list for invalid input. [docs/DESIGN.md](docs/DESIGN.md) documents every endpoint.

## How it is verified

- **Tests.** `./sched test` runs both suites; it needs only Docker.
  - The API's integration tests run against a real PostgreSQL.
  - The concurrency tests release real threads together, and force both orders of the race
    between a report and recovery.
  - Each guard also had a mutation check: with the guard removed, its tests failed.
- **The demo** checks 17 claims and exits with an error on any violation.
- **CI** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs both test suites and the
  demo on every push.
- **Benchmarks.** `./sched bench` measures dispatch, submission, and training throughput, and fails
  if any job was claimed or completed more than once.
  [docs/BENCHMARKS.md](docs/BENCHMARKS.md) has the method and one laptop's results. It also has
  the experiment that found per-job logging costs about a third of dispatch throughput.
- **Logs** are JSON lines with shared ids. `./sched logs --job 7` follows one job through the API
  and every worker.

## Documentation

| File | Contents |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | Schema, lifecycle, API contract, guarantees, transactions and time, the worker, logs, CI, and the decision log |
| [docs/PROGRESS.md](docs/PROGRESS.md) | What was built, how each part was verified, and the known limitations |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | The benchmark's method, results, and experiments |

**Built with:**

- Java 21, Spring Boot 4.1, Spring JDBC (explicit SQL, no JPA), Flyway, and PostgreSQL 18;
- Python 3.14, PyTorch 2.14 on CPU, and uv;
- Docker Compose, JUnit, Testcontainers, pytest, and GitHub Actions.

Every version is pinned exactly.

## Layout

```
sched           The command-line tool
api/            Spring Boot service: REST API, scheduling rules, all database access
worker/         Python worker: claim → train (PyTorch, CPU) → report
compose.yaml    The stack: PostgreSQL, the API, and workers
docs/           DESIGN.md, PROGRESS.md, BENCHMARKS.md, and benchmarks/ (each run's data)
examples/       Sample batches
scripts/        demo.sh, benchmark.sh and bench/, make_sweep.py (the sweep grid), mvnw-docker.sh
.github/        CI: API tests, worker tests, and the demo on every push
```
