# Benchmarks

What the scheduler costs, measured on one machine. The results below are generated from runs of
`scripts/benchmark.sh` and are observations of those runs, not guarantees. Numbers from another
machine, or from the same one on another day, will differ. Rerun it rather than quote these.

```bash
scripts/benchmark.sh                     # everything, about 10 minutes
scripts/benchmark.sh api                 # only the API measurements
REPS=1 scripts/benchmark.sh training     # one round of the training measurement
PUBLISH=1 scripts/benchmark.sh           # also regenerate the Results section below
```

Each run writes `docs/benchmarks/<run>.json` (every measurement, with the environment) and
`<run>.md` (the tables). It exits non-zero if any correctness check failed.

## Method

- **An isolated stack:**
  - `scripts/benchmark.sh` starts its own Compose project (`mlsched-bench`, API on `:19080`),
    built from the working tree, with the stack's normal settings.
  - The one exception is that idle workers poll at least every 0.5 s. A worker never sleeps while
    jobs are queued, so this changes only how fast a batch gets going.
  - It records the environment first: the host and its power source, Docker's CPUs and memory,
    the load from other containers, the commit, PostgreSQL's durability settings, and the CPU limits
    and torch threads that were actually in effect.
- **The client** (`scripts/bench/bench.py`, standard library only) runs inside the stack's network,
  in the worker's base image, so macOS's port forwarding is not part of any measurement.
  - Each fake worker or client is a process of its own. In a trial run, one process's interpreter
    lock became the limit at 16 fake workers.
  - The client's own CPU use is reported, so it can be checked for saturation.
- **Repetitions:** every measurement is repeated (3 times by default), interleaved: each round
  covers every level once, so drift during the run affects all levels alike. The tables show the
  median, with the range in brackets.
- **Warm-up:** the JVM is warmed up with 5,000 dispatched jobs before anything is measured, and
  each measurement has its own warm-up (below).

The measurements:

1. **Dispatch without training.**
   - The database is reset, and 500 warm-up jobs and then 5,000 measured jobs are queued, in
     experiments of 500.
   - Then 1, 2, 4, 8, or 16 fake workers each loop *claim → complete at once, with fixed metrics*,
     until the queue is empty.
   - Only the measured jobs count. The window runs from the first measured claim to the last
     measured completion.
   - Afterwards, SQL checks that every queued job was claimed exactly once and completed exactly
     once. The run fails otherwise.
2. **Submission:** one client posts 33 experiments of 1, 100, or 500 jobs, each with a fresh
   `Idempotency-Key`. The first 3 are not measured.
3. **HTTP without the database:** `GET /actuator`, which goes through Tomcat and Spring MVC but not
   PostgreSQL, from 1 and 16 clients, with 2,000 requests each. It shows how much of a claim's
   latency HTTP accounts for.
4. **The disk's flush rate:** `pg_test_fsync` in the database's volume. It reports the
   `fdatasync` of one 8 kB write, the method PostgreSQL uses for its WAL here.
5. **Training on real workers:**
   - The run uses 1, 2, or 4 workers, each with its normal 1-CPU limit and one torch thread.
   - After a reset, 10 warm-up jobs per worker are queued ahead of the 200-job grid
     (`scripts/make_sweep.py --seeds 2`), so every worker is busy before the first measured job
     starts.
   - The window runs from the first measured job's `startedAt` to the last one's `finishedAt`,
     both on the database's clock.

**Columns:**

| Column | Meaning |
|---|---|
| Jobs/s | Measured jobs divided by the window |
| p50, p99 | Nearest-rank percentiles of the measured requests' latencies, from sending the request to reading the last byte of the response |
| API or PostgreSQL CPU per job | The container's CPU time during the dispatch (from its cgroup's `usage_usec`), divided by all jobs dispatched, warm-up included |
| API, PostgreSQL, client cores | The same CPU time divided by the dispatch's wall time. For the client, its processes' own CPU time |
| Speed-up, efficiency | Jobs/s divided by jobs/s with 1 worker; then speed-up divided by the number of workers |
| Training loop share | The workers' total time inside the training loop (the metrics' `trainingSeconds`), divided by workers × window |
| Outside the loop, per job | (workers × window − that total) ÷ jobs. It covers everything else a worker spends on a job: the claim and report round trips, building and evaluating the model, and starting and stopping the heartbeat thread. It also includes workers idling at the end of the batch |

**What this does not show:**

- **Durability on real hardware.** Docker Desktop runs containers in a VM. A flush measured inside
  it does not show whether the write reached the SSD.
- **Response times at a given arrival rate.** The load is closed-loop: each fake worker has one
  request in flight and sends the next when it returns. The percentiles are service times at that
  concurrency. Queueing delay under an open arrival rate (coordinated omission) is not measured.
- **What real workers achieve through the API.** Fake workers never train, so the dispatch numbers
  bound the scheduler's own cost per job. The training numbers show real workers.
- **Exactly-once execution.** Every job ran once in these runs because no lease expired. After an
  expired lease, the design lets a job run more than once (see DESIGN.md).
- **An idle machine.** The client, the stack, and any other containers share the CPUs. The
  environment table records the other containers' load and the power source.
- **CI.** It doesn't run this, because a shared runner's timings are not benchmarks.

## Results

<!-- results:begin -->
<!-- Generated by scripts/bench/report.py; do not edit by hand. -->

Raw data: [20260930T161444Z.json](benchmarks/20260930T161444Z.json).

Run `20260930T161444Z`: 3 runs of every measurement, interleaved. Tables show the median over the runs, with the range in brackets. The [method](#method) section explains the columns.

### Environment

|  |  |
|---|---|
| Host | Apple M4 Pro, 12 cores (8 performance, 4 efficiency), 24.0 GiB, macOS 26.6.2; power: Battery Power; low power mode off; load average 5.88 at the start |
| Docker | Docker Desktop 29.2.0, Compose 5.0.2; 12 CPUs and 7.7 GiB for containers, kernel 6.12.67-linuxkit |
| Other containers | 23 running outside the benchmark, using 0.37 CPU cores together when measured at the start |
| Code | commit `e0b06ab`; api/, worker/, and compose.yaml as committed |
| PostgreSQL | 18.6 (Debian 18.6-1.pgdg13+2); synchronous_commit on, fsync on, wal_sync_method fdatasync, shared_buffers 128MB |
| CPU limits | API: none; PostgreSQL: none |
| API settings | connection pool of 10, log level INFO for the application's loggers (the stack's defaults) |
| Disk flush | one 8 kB write plus fdatasync took 95 µs (10,485 per second), by pg_test_fsync in the database's volume |
| API database connections | 10 open at the end of the run |

### Dispatch without training

| Fake workers | Jobs/s | Claim p50 | Claim p99 | Complete p50 | Complete p99 |
|---|---|---|---|---|---|
| 1 | 578 [576–639] | 0.77 ms | 1.42 ms | 0.75 ms | 1.44 ms |
| 2 | 1,023 [953–1,043] | 0.96 ms | 1.67 ms | 0.90 ms | 1.57 ms |
| 4 | 1,544 [1,521–1,557] | 1.27 ms | 2.31 ms | 1.20 ms | 2.11 ms |
| 8 | 2,429 [2,422–2,479] | 1.58 ms | 3.37 ms | 1.53 ms | 3.12 ms |
| 16 | 2,967 [2,902–2,988] | 2.56 ms | 5.70 ms | 2.46 ms | 5.60 ms |

| Fake workers | API CPU per job | PostgreSQL CPU per job | API cores | PostgreSQL cores | Client cores |
|---|---|---|---|---|---|
| 1 | 0.56 ms | 0.60 ms | 0.35 | 0.34 | 0.16 |
| 2 | 0.67 ms | 0.66 ms | 0.68 | 0.67 | 0.29 |
| 4 | 0.78 ms | 0.75 ms | 1.18 | 1.16 | 0.47 |
| 8 | 0.70 ms | 0.72 ms | 1.68 | 1.73 | 0.79 |
| 16 | 0.72 ms | 0.69 ms | 2.15 | 2.01 | 1.03 |

Checked after every run: all 15 of 15 runs claimed each of their 5,500 queued jobs exactly once and completed it exactly once (SUCCEEDED on attempt 1, with one attempt row), without errors.

### HTTP without the database (`GET /actuator`)

| Clients | Requests/s | p50 | p99 | Client cores |
|---|---|---|---|---|
| 1 | 5,855 [5,342–6,020] | 0.16 ms | 0.40 ms | 0.48 |
| 16 | 47,434 [45,417–47,925] | 0.29 ms | 0.99 ms | 4.61 |

### Submission (`POST /experiments` with an `Idempotency-Key`)

| Jobs per request | p50 | p90 | Max | Jobs stored per second at p50 |
|---|---|---|---|---|
| 1 | 1.47 ms | 2.59 ms | 10.5 ms | 681 |
| 100 | 7.83 ms | 8.86 ms | 10.7 ms | 12,772 |
| 500 | 31.7 ms | 33.9 ms | 64.9 ms | 15,752 |

30 requests per size and run, after 3 not measured; p50 and p90 are medians over the runs, and max is the largest of any run.

### Training on real workers

| Workers | Jobs/s | Speed-up | Efficiency | Training loop share | Outside the loop, per job | Training loop per job (p50) |
|---|---|---|---|---|---|---|
| 1 | 5.4 [5.4–5.5] | 1.00× | 100% | 96% [96%–96%] | 7.65 ms | 162 ms |
| 2 | 11.2 [9.5–11.2] | 2.06× | 103% | 96% [95%–96%] | 8.16 ms | 160 ms |
| 4 | 20.6 [19.6–21.2] | 3.78× | 95% | 95% [95%–95%] | 9.29 ms | 167 ms |

200 measured jobs per run, after 10 warm-up jobs per worker queued ahead of them. As observed, each worker ran with a 1-CPU limit and 1 torch thread. Checked after every run: each job SUCCEEDED on its first attempt, and every worker took part.

<!-- results:end -->

**Notes on this run,** checked against its JSON:

- **The JVM was still compiling during the first measured run.** That run was 1 fake worker in
  round 1. It used 1.33 ms of API CPU per job, against 0.56 ms in the later rounds at that level.
  Its throughput stayed within the other rounds' range, and the medians absorb the difference.
- **Real workers scaled almost linearly.** 2 and 4 workers ran the grid 2.06× and 3.78× as fast as
  one. The efficiency above 100% is within the spread: one of the three 2-worker runs took 21.0 s,
  where the other two took 17.9 s.
- **Round 2 of the training ran on slower CPUs.**
  - Its 2- and 4-worker runs had median training loops of 183 and 179 ms per job, against
    159–167 ms in every other run. So the CPUs themselves were slower then; it wasn't the
    scheduling.
  - The laptop was on battery.
- **Outside its training loop, a worker spends about 8–9 ms per job.** The claim and the completion
  account for about 1.5 ms of that, going by their p50s in the dispatch measurement. The rest is the
  other work listed under Columns.

## Experiments: what limits dispatch?

Dispatch stopped scaling well between 8 and 16 fake workers, while the API and PostgreSQL each used
about 2 of the 12 CPUs (see the resources table above). There were two suspects:

- the API's pool of 10 database connections, fewer than 16 concurrent requests;
- the two INFO events every job has logged since 3.2, `job.claimed` and `result.accepted`, which
  the API writes synchronously.

`scripts/benchmark.sh api` was run four times within 20 minutes, changing one setting at a time:

- twice with the defaults;
- once with `BENCH_POOL_SIZE=20`;
- once with `BENCH_API_LOG_LEVEL=WARN`, which silences those events.

<!-- experiments:begin -->
<!-- Generated by scripts/bench/report.py; do not edit by hand. -->

| Run | Connection pool | Log level | Jobs/s, 1 fake worker | Jobs/s, 2 fake workers | Jobs/s, 4 fake workers | Jobs/s, 8 fake workers | Jobs/s, 16 fake workers | API CPU per job at 16 | API cores at 16 |
|---|---|---|---|---|---|---|---|---|---|
| [20260930T161444Z](benchmarks/20260930T161444Z.json) | 10 | INFO | 578 | 1,023 | 1,544 | 2,429 | 2,967 | 0.72 ms | 2.15 |
| [20260930T162835Z](benchmarks/20260930T162835Z.json) | 10 | INFO | 590 | 1,037 | 1,551 | 2,435 | 2,282 | 0.88 ms | 2.02 |
| [20260930T162220Z](benchmarks/20260930T162220Z.json) | 20 | INFO | 603 | 1,007 | 1,528 | 2,362 | 3,174 | 0.66 ms | 2.24 |
| [20260930T162509Z](benchmarks/20260930T162509Z.json) | 10 | WARN | 846 | 1,595 | 2,338 | 3,533 | 4,540 | 0.60 ms | 2.73 |

Medians over each run's repetitions of the dispatch measurement; each run's own report has the ranges, latencies, and environment.
<!-- experiments:end -->

What they show:

- **Per-job logging costs about a third of dispatch throughput.** Without the two INFO events:
  - 1 fake worker dispatched 846 jobs/s, against 578 and 590 in the default runs;
  - 8 fake workers dispatched 3,533, against 2,429 and 2,435;
  - the claim and completion p50s each fell by about 0.2 ms.

  The two default runs agree within about 2% at 1–8 fake workers, although they started under
  different background loads. So load differences between these runs are small next to this effect.
- **Most of that cost is waiting, not computing.** At 1 fake worker a job took about 0.5 ms less, but
  the API's CPU per job fell by only 0.15–0.22 ms. The rest is time the requests spent waiting. The
  most plausible cause is writing each JSON line to stdout: the claim and the completion do it
  synchronously, inside their transactions. That is an inference from these numbers; the write
  itself was not timed.
- **Up to 8 fake workers, the pool is not a limit.** With 20 connections open (observed at the end
  of that run), throughput at 1–8 fake workers stayed within 5% of both default runs.
- **At 16 fake workers, these runs can't tell.**
  - The pool-20 run was faster than both default runs: 3,146–3,381 jobs/s, against 2,902–2,988 and
    1,756–2,854.
  - But 16 is the least reproducible level here: the second default run's rounds alone spread over
    1,756–2,854.
  - And the runs started under different background loads (see each run's environment).

**A likely improvement, not made here:** log these events after the transaction commits, instead of
inside it.

- It would take the write off the transaction's path.
- It would also fix the caveat in DESIGN.md that a line can describe a change that was rolled back.
- Its effect would need a run of its own.
