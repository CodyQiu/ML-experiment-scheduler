# ML Experiment Scheduler

Submit a batch of small ML training configurations over REST. Python workers claim the jobs, train
a small CPU-only PyTorch model on a deterministic synthetic dataset, and report metrics. The
project is about correctness under concurrency and failure: atomic claims, leases, fencing tokens,
bounded retries, and idempotent submission, all backed by PostgreSQL.

**Status:** milestones 1 (end-to-end MVP) and 2 (reliability) are complete. That covers atomic
claims, leases and heartbeats, crash recovery, failure reports with bounded retries, idempotent
submission, safe repeated completion, and a checked end-to-end demo. Milestone 3 has begun: a CI
workflow that runs both test suites and the demo on every push is in place, replayed locally but not
yet run on GitHub. Structured logs and benchmarks are next.

## See it handle failure

```bash
scripts/demo.sh    # about 100 s; needs Docker, jq, curl, python3
```

It runs in its own Compose project, with API on `:18080` and 10 s leases, so it never touches the
stack below. It walks through:

1. a 200-configuration sweep shared by 3 workers, then the best configurations;
2. a worker SIGKILLed mid-training, whose job another worker finishes after the lease expires;
3. a worker frozen past its lease, whose old attempt then tries to report: rejected while its
   replacement runs, rejected again after it succeeds, and the worker itself is fenced when it
   wakes.

Every claim is checked (`[ok]`/`[FAIL]`), and the exit status is non-zero on any violation.
`scripts/demo.sh crash stale` runs only some scenarios, and `KEEP=1` leaves the stack up to
explore. See
[docs/PROGRESS.md](docs/PROGRESS.md) and [docs/DESIGN.md](docs/DESIGN.md).

## Quick start

Requires Docker with Compose v2 and `jq`. A local JDK or Python is optional.

```bash
docker compose up -d --build --wait     # PostgreSQL 18, the API on localhost:8080, one worker

# Submit six configs and keep the new experiment's id. With an Idempotency-Key, a retry of this
# exact request returns the same experiment instead of creating another one.
ID=$(curl -s -X POST localhost:8080/experiments -H 'Content-Type: application/json' \
       -H 'Idempotency-Key: quickstart-1' --data @examples/small-batch.json | jq .id)

# Watch progress until every job has finished (a few seconds; Ctrl-C to quit)
while sleep 1; do curl -s localhost:8080/experiments/$ID | jq -c .progress; done

# Compare the successful configurations, best first
curl -s "localhost:8080/experiments/$ID/best?limit=5" | jq -r '.jobs[] |
  "#\(.rank) \(.valAccuracy)  \(.config.optimizer) lr=\(.config.learningRate) \(.config.hiddenUnits)x\(.config.hiddenLayers)"'

docker compose logs worker              # claim → train → report, one line each

# Failures at a glance, and one job's full history
curl -s localhost:8080/experiments/$ID/jobs | jq -c '.jobs[] | select(.lastError) | {id, state, lastError}'
curl -s localhost:8080/jobs/1/attempts | jq
```

**More workers and a bigger batch.** This runs the 100-config grid; add `--seeds 2` for 200 jobs.

```bash
docker compose up -d --scale worker=3 --wait
python3 scripts/make_sweep.py | curl -s -X POST localhost:8080/experiments \
  -H 'Content-Type: application/json' --data @- | jq '{id, progress}'
```

**Crash recovery.** Kill a worker mid-job. After its 30 s lease expires, the sweep re-queues the job
and another worker runs attempt 2:

```bash
docker compose up -d --scale worker=2 --wait
curl -s -X POST localhost:8080/experiments -H 'Content-Type: application/json' -d '{"name":"crash","task":"synthetic-mlp-v1",
  "jobs":[{"seed":0,"config":{"learningRate":0.01,"hiddenUnits":256,"hiddenLayers":4,"batchSize":8,"epochs":100,"optimizer":"adam","weightDecay":0.0}}]}' | jq .id
curl -s localhost:8080/experiments/<id>/jobs | jq '.jobs[0] | {id, state, workerId}'   # note the workerId prefix
docker kill -s KILL <worker container whose ID starts with that prefix>   # see: docker compose ps worker
docker compose logs -f api | grep -E 'Claimed|expired|Accepted'           # attempt 1 expires, attempt 2 wins
```

**Stopping.**

```bash
docker compose stop worker    # each worker finishes and reports its current job, then exits
docker compose down           # stop everything; add -v to also delete the database volume
```

A rejected batch gets per-field errors, and nothing is stored:

```bash
curl -s -X POST localhost:8080/experiments -H 'Content-Type: application/json' \
  --data @examples/invalid-batch.json | jq
```

### Act as a worker by hand

Stop the real workers first (`docker compose up -d --scale worker=0`), or they will claim the jobs
before you do. A claim's lease lasts 30 s. Heartbeat to extend it, or finish within it; after that,
the job is taken back and your attempt id is rejected.

```bash
A=$(curl -s -X POST localhost:8080/worker/jobs/claim -H 'Content-Type: application/json' -d '{"workerId":"manual-1"}')
echo "$A" | jq                                  # 200 with the assignment, or empty on 204 (nothing queued)
JOB=$(jq -r .jobId <<<"$A"); ATTEMPT=$(jq -r .attemptId <<<"$A")
METRICS='{"valAccuracy":0.91,"valLoss":0.25,"trainLoss":0.2,"trainingSeconds":1.5}'

curl -s -X POST localhost:8080/worker/jobs/$JOB/heartbeat -H 'Content-Type: application/json' \
  -d "{\"attemptId\":\"$ATTEMPT\"}" | jq                           # 200: lease extended by 30 s
curl -s -X POST localhost:8080/worker/jobs/$JOB/complete -H 'Content-Type: application/json' \
  -d "{\"attemptId\":\"$ATTEMPT\",\"metrics\":$METRICS}" | jq     # 200: accepted and final
curl -s -X POST localhost:8080/worker/jobs/$JOB/complete -H 'Content-Type: application/json' \
  -d "{\"attemptId\":\"$ATTEMPT\",\"metrics\":$METRICS}" | jq     # 200 replayed: a lost-ack retry changes nothing
```

## Tests

The API's integration tests run against real PostgreSQL through Testcontainers, so Docker must be
running.

```bash
cd api && ./mvnw verify          # with a local JDK 21
scripts/mvnw-docker.sh verify    # without one: runs the Maven Wrapper in a JDK 21 container

cd worker && uv run pytest       # worker tests; uv installs the locked environment into worker/.venv
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs both suites and `scripts/demo.sh`
on every push, on a fresh GitHub-hosted runner. The demo's output appears in each run's summary.

## Layout

```
.github/        workflows/ci.yml: API tests, worker tests, and the demo on every push
api/            Spring Boot service: REST API, scheduling rules, all database access
  src/main/resources/db/migration/   Flyway SQL migrations
worker/         Python worker: claim → train (PyTorch, CPU) → report
  scheduler_worker/   task.py (dataset, model, metrics), client.py (HTTP), worker.py (loop)
compose.yaml    Local stack
docs/           DESIGN.md (schema, lifecycle, API contract, guarantees, worker), PROGRESS.md
examples/       Sample request bodies
scripts/        demo.sh (checked end-to-end demo), make_sweep.py (grid sweeps), mvnw-docker.sh
```
