# Progress

_Last updated: 2026-09-28_

## Status

| Increment | Scope | Status |
|---|---|---|
| 1.1 | Repo skeleton, V1 schema, submission + retrieval API, error format, Compose (postgres + api), integration tests | done |
| 1.2 | Worker protocol in the API: atomic claim, fenced completion, concurrency tests | next |
| 1.3 | Python worker with real PyTorch training, Compose worker service, end-to-end run | after 1.2 |
| M2 | Leases, heartbeats, recovery sweeper, failure reporting, bounded retries, attempt history, idempotent submission, safe repeated completion, race tests, kill-a-worker demo | planned |
| M3 | CI, structured logs, architecture README, recovery demo write-up, benchmarks | planned |

## Completed in 1.1

- `api/`: Spring Boot 4.1.1 on Java 21 with the Maven Wrapper (Maven 3.9.16). Explicit SQL through
  `JdbcClient` and `JdbcTemplate`.
- `V1__create_experiments_and_jobs.sql`: `experiments` and `jobs`, with the claim-identity columns
  the MVP needs, CHECK constraints for the lifecycle invariants, and a partial index for claims.
- `POST /experiments` (validated, one transaction), `GET /experiments/{id}` (progress counts from one
  statement), `GET /experiments/{id}/jobs`, and `GET /jobs/{id}`.
- RFC 9457 problem details with a stable `code` and per-field `errors`. Strict JSON parsing: unknown
  fields, type coercion, fractional integers, and duplicate keys are all rejected.
- `compose.yaml`: PostgreSQL 18.6 and the API, with health checks and a persistent volume.
- `scripts/mvnw-docker.sh`: runs Maven, including the Testcontainers tests, without a local JDK.
- `examples/small-batch.json` (6 jobs) and `examples/invalid-batch.json`.

## Verified (2026-09-28: macOS arm64, Docker Desktop 29.2.0, VM with 12 CPUs / 8 GB)

- `scripts/mvnw-docker.sh verify`: 30 tests, 0 failures, against PostgreSQL 18.6 through Testcontainers.
- Two mutation checks confirmed the tests catch real breakage:
  - Disabling `FAIL_ON_UNKNOWN_PROPERTIES` makes the misspelled-field test fail (201 instead of 400).
  - Removing the submission transaction makes the atomicity test fail (an orphaned experiment row).
- `docker compose up -d --build --wait` brings both services up healthy. Submission, retrieval,
  400, and 404 were exercised with curl. Data survives an API restart, and Flyway validates V1
  instead of re-applying it.

## Known limitations (current)

- No worker endpoints yet, so jobs stay `QUEUED`.
- The MVP limitations listed in [DESIGN.md](DESIGN.md#mvp): no crash recovery, retries, failure
  reports, idempotent submission, or completion replay.
- The request body is parsed before the 500-job limit is checked, and body size is not capped
  separately. That is acceptable for a local single-user service.
- No CI yet (M3).
- The dev machine has no local JDK. Java builds run in Docker unless JDK 21 is installed.

## Next: increment 1.2 (worker protocol, API side)

1. `POST /worker/jobs/claim`: the single-statement `FOR UPDATE SKIP LOCKED` claim from DESIGN.md.
   Returns `200` with an assignment, or `204` when nothing is queued.
2. `POST /worker/jobs/{id}/complete`: guarded `UPDATE` on `state` and `current_attempt_id`, decided by
   the affected-row count. `409 ATTEMPT_NOT_CURRENT` otherwise. Metrics are validated (`valAccuracy`
   in [0, 1], finite losses, and so on).
3. Tests against real PostgreSQL:
   - N threads released together by a latch race to claim M jobs. No job is claimed twice, and the
     number of claims is min(N, M).
   - Claims follow FIFO order.
   - An empty queue returns 204.
   - A completion with the wrong attempt id is rejected and leaves the row unchanged.
   - A completion on an already succeeded job is rejected and leaves the result unchanged.
