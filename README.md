# ML Experiment Scheduler

Submit a batch of small ML training configurations over REST. Python workers claim the jobs, train
a small CPU-only PyTorch model on a deterministic synthetic dataset, and report metrics. The
project is about correctness under concurrency and failure: atomic claims, leases, fencing tokens,
bounded retries, and idempotent submission, all backed by PostgreSQL.

**Status:** milestone 1 is in progress. Submission and retrieval work, and workers come next. See
[docs/PROGRESS.md](docs/PROGRESS.md).

## Quick start

Requires Docker with Compose v2. A local JDK is optional.

```bash
docker compose up -d --build --wait          # PostgreSQL 18 + API on localhost:8080

curl -i -X POST localhost:8080/experiments \
  -H 'Content-Type: application/json' --data @examples/small-batch.json
curl -s localhost:8080/experiments/1 | jq
curl -s localhost:8080/experiments/1/jobs | jq '.jobs[] | {id, jobIndex, state, config}'

# Rejected with per-field errors, and nothing is stored:
curl -s -X POST localhost:8080/experiments \
  -H 'Content-Type: application/json' --data @examples/invalid-batch.json | jq

docker compose down        # stop the stack; add -v to also delete the database volume
```

## Tests

The integration tests run against real PostgreSQL through Testcontainers, so Docker must be running.

```bash
cd api && ./mvnw verify          # with a local JDK 21
scripts/mvnw-docker.sh verify    # without one: runs the Maven Wrapper in a JDK 21 container
```

## Layout

```
api/            Spring Boot service: REST API, scheduling rules, all database access
  src/main/resources/db/migration/   Flyway SQL migrations
compose.yaml    Local stack
docs/           DESIGN.md (schema, lifecycle, API contract, guarantees), PROGRESS.md
examples/       Sample request bodies
scripts/        Developer helpers
```
