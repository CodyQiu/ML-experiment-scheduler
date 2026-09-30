#!/usr/bin/env bash
# Measures the scheduler on this machine, and writes what it observed to docs/benchmarks/<run>.json
# and <run>.md. Every number in them comes from this run; nothing is typed in by hand.
#
#   scripts/benchmark.sh              everything, about 10 minutes
#   scripts/benchmark.sh api          only the API: dispatch without training, submission, HTTP
#                                     without the database, and the disk's flush rate
#   scripts/benchmark.sh training     only training throughput with 1, 2, and 4 real workers
#   REPS=1 scripts/benchmark.sh       fewer repetitions of each measurement (default 3)
#   PUBLISH=1 scripts/benchmark.sh    also replace the results section of docs/BENCHMARKS.md
#   BENCH_POOL_SIZE=20, BENCH_API_LOG_LEVEL=WARN
#                                     change the API's connection pool or application log level, for
#                                     the experiments in docs/BENCHMARKS.md (defaults: 10 and INFO)
#
# It runs in its own Compose project (mlsched-bench, API on localhost:19080), like scripts/demo.sh,
# and removes it afterwards unless KEEP=1. docs/BENCHMARKS.md describes the method.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

PROJECT=mlsched-bench
export API_PORT="${BENCH_API_PORT:-19080}" POSTGRES_PORT="${BENCH_POSTGRES_PORT:-16432}"
API="http://localhost:$API_PORT"
export BENCH_POOL_SIZE="${BENCH_POOL_SIZE:-10}" BENCH_API_LOG_LEVEL="${BENCH_API_LOG_LEVEL:-INFO}"
REPS="${REPS:-3}"
CONCURRENCY=(1 2 4 8 16)  # fake workers in the dispatch measurement
WORKERS=(1 2 4)           # real workers in the training measurement
WARMUP_JOBS=500           # dispatched before the measured jobs of each run, not measured
MEASURED_JOBS=5000
SUBMIT_SIZES=(1 100 500)
SUBMIT_REQUESTS=30
BASELINE_CONCURRENCY=(1 16)
BASELINE_REQUESTS=2000    # per client

RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
PIECES=$(mktemp -d)
FAILURES=0
START=$SECONDS

say()    { printf '%4ss  %s\n' "$((SECONDS - START))" "$*"; }
header() { printf '\n== %s\n' "$*"; }
fail()   { printf '       [FAIL] %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
die()    { printf 'benchmark: %s\n' "$*" >&2; exit 2; }

dc()    { docker compose -p "$PROJECT" -f compose.yaml -f scripts/bench/compose.bench.yaml "$@"; }
psql_() { dc exec -T postgres psql -U scheduler -d scheduler -v ON_ERROR_STOP=1 -At "$@" </dev/null; }

# Runs scripts/bench/bench.py in the stack's network and prints its JSON; stops the run on an error.
bench() {
  local output
  if ! output=$(dc run --rm -T bench "$@" </dev/null 2>"$PIECES/bench.err"); then
    tail -n 20 "$PIECES/bench.err" >&2
    die "bench.py $1 failed"
  fi
  printf '%s\n' "$output"
}

reset_database() {
  psql_ -c 'TRUNCATE experiments, jobs, attempts RESTART IDENTITY' >/dev/null || die "could not reset the database"
}

# CPU time a container has used so far, in microseconds, from its cgroup (v2).
cpu_usec() {
  docker exec "$1" cat /sys/fs/cgroup/cpu.stat </dev/null | awk '$1 == "usage_usec" {print $2}'
}

# {"api": µs, "postgres": µs, "workers": µs summed over the running worker containers}
cpu_snapshot() {
  local worker total=0
  for worker in $(dc ps -q worker); do
    total=$((total + $(cpu_usec "$worker")))
  done
  jq -n --argjson api "$(cpu_usec "$(dc ps -q api)")" --argjson postgres "$(cpu_usec "$(dc ps -q postgres)")" \
    --argjson workers "$total" '{api: $api, postgres: $postgres, workers: $workers}'
}

# --- The environment -----------------------------------------------------------------------------

host_json() {
  if [ "$(uname -s)" = Darwin ]; then
    jq -n --arg cpu "$(sysctl -n machdep.cpu.brand_string)" --argjson cores "$(sysctl -n hw.ncpu)" \
      --arg performance "$(sysctl -n hw.perflevel0.physicalcpu 2>/dev/null)" \
      --arg efficiency "$(sysctl -n hw.perflevel1.physicalcpu 2>/dev/null)" \
      --argjson memory "$(sysctl -n hw.memsize)" --arg os "macOS $(sw_vers -productVersion)" \
      --arg power "$(pmset -g batt | head -1 | sed -E "s/^Now drawing from '(.*)'.*/\1/")" \
      --arg lowPower "$(pmset -g | awk '$1 == "lowpowermode" {print $2}')" \
      --arg load "$(sysctl -n vm.loadavg | tr -d '{}' | awk '{print $1}')" \
      '{cpu: $cpu, cores: $cores, performanceCores: ($performance | tonumber? // null),
        efficiencyCores: ($efficiency | tonumber? // null), memoryBytes: $memory, os: $os, power: $power,
        lowPowerMode: ($lowPower == "1"), loadAverage1m: ($load | tonumber)}'
  else
    jq -n --arg cpu "$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')" --argjson cores "$(nproc)" \
      --argjson memory "$(($(awk '/MemTotal/ {print $2}' /proc/meminfo) * 1024))" \
      --arg os "$(. /etc/os-release && echo "$PRETTY_NAME")" --arg load "$(cut -d' ' -f1 /proc/loadavg)" \
      '{cpu: $cpu, cores: $cores, memoryBytes: $memory, os: $os, loadAverage1m: ($load | tonumber)}'
  fi
}

# Containers of other projects share the machine; their names are not recorded, only their load.
other_containers_json() {
  local names cpu=0 count
  names=$(docker ps --format '{{.Names}}' | grep -v "^$PROJECT-" || true)
  count=$(grep -c . <<<"$names" || true)
  if [ "$count" -gt 0 ]; then
    # shellcheck disable=SC2086  # one argument per container name
    cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' $names | tr -d '%' | awk '{s += $1} END {printf "%.1f", s}')
  fi
  jq -n --argjson count "$count" --argjson cpu "$cpu" '{running: $count, cpuPercent: $cpu}'
}

# Container CPU limits as Docker applied them: a number of CPUs, or null for none.
cpu_limit() {  # container
  docker inspect --format '{{.HostConfig.NanoCpus}}' "$1" | jq 'if . == 0 then null else . / 1e9 end'
}

environment_json() {
  local settings
  settings=$(psql_ -F '|' -c "SELECT current_setting('server_version'), current_setting('synchronous_commit'),
    current_setting('fsync'), current_setting('wal_sync_method'), current_setting('shared_buffers'),
    current_setting('max_connections')")
  jq -n --argjson host "$(host_json)" --argjson others "$OTHER_CONTAINERS" \
    --argjson docker "$(docker info --format '{"server": "{{.ServerVersion}}", "os": "{{.OperatingSystem}}", "kernel": "{{.KernelVersion}}", "cpus": {{.NCPU}}, "memoryBytes": {{.MemTotal}}}')" \
    --arg compose "$(docker compose version --short)" --arg commit "$(git rev-parse --short HEAD)" \
    --argjson dirty "$([ -n "$(git status --porcelain -- api worker compose.yaml)" ] && echo true || echo false)" \
    --arg postgres "$settings" --argjson apiCpus "$(cpu_limit "$(dc ps -q api)")" \
    --argjson postgresCpus "$(cpu_limit "$(dc ps -q postgres)")" \
    --argjson pool "$BENCH_POOL_SIZE" --arg logLevel "$BENCH_API_LOG_LEVEL" \
    --argjson parameters "$(jq -n --argjson reps "$REPS" --argjson warmup "$WARMUP_JOBS" --argjson measured "$MEASURED_JOBS" \
      --argjson requests "$SUBMIT_REQUESTS" --argjson baseline "$BASELINE_REQUESTS" \
      --argjson concurrency "$(printf '%s\n' "${CONCURRENCY[@]}" | jq -s .)" \
      --argjson sizes "$(printf '%s\n' "${SUBMIT_SIZES[@]}" | jq -s .)" \
      --argjson clients "$(printf '%s\n' "${BASELINE_CONCURRENCY[@]}" | jq -s .)" \
      --argjson workers "$(printf '%s\n' "${WORKERS[@]}" | jq -s .)" \
      '{reps: $reps, dispatchConcurrency: $concurrency, dispatchWarmupJobs: $warmup, dispatchMeasuredJobs: $measured,
        submitSizes: $sizes, submitRequests: $requests, baselineClients: $clients, baselineRequestsPerClient: $baseline,
        trainingWorkers: $workers, trainingBatch: "scripts/make_sweep.py --seeds 2", trainingWarmupJobsPerWorker: 10}')" \
    '($postgres | split("|")) as $pg |
     {host: $host, docker: ($docker + {compose: $compose}), otherContainers: $others,
      code: {commit: $commit, systemUnderTestChanged: $dirty},
      postgres: {version: $pg[0], synchronousCommit: $pg[1], fsync: $pg[2], walSyncMethod: $pg[3],
                 sharedBuffers: $pg[4], maxConnections: ($pg[5] | tonumber)},
      limits: {apiCpus: $apiCpus, postgresCpus: $postgresCpus},
      settings: {apiConnectionPool: $pool, apiLogLevel: $logLevel},
      parameters: $parameters}'
}

# The API's open database connections: its connection pool, once the load has filled it.
api_connections_json() {
  psql_ -c "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'
    AND usename = 'scheduler' AND pid <> pg_backend_pid()" | jq -R '{apiDatabaseConnections: tonumber}'
}

# --- The stack -----------------------------------------------------------------------------------

start_stack() {
  local tool output
  for tool in docker curl jq python3; do
    command -v "$tool" >/dev/null || die "$tool is required"
  done
  if curl -s -o /dev/null "$API/actuator/health"; then
    die "something already answers on $API (set BENCH_API_PORT to use another port)"
  fi
  say "measuring what else runs on this machine first"
  OTHER_CONTAINERS=$(other_containers_json)
  say "starting $PROJECT: PostgreSQL and the API on $API, no workers yet (the first run builds images)"
  if ! output=$(dc up -d --build --wait --scale worker=0 2>&1); then
    printf '%s\n' "$output" | tail -n 20 >&2
    die "the stack did not start; see: docker compose -p $PROJECT logs"
  fi
  environment_json >"$PIECES/environment.json" || die "could not record the environment"
  say "environment: $(jq -r '"\(.host.cpu), \(.host.cores) cores, \(.docker.cpus) CPUs for Docker; commit \(.code.commit)"' "$PIECES/environment.json")"
}

stop_stack() {
  if [ "${KEEP:-0}" = 1 ]; then
    say "KEEP=1: $PROJECT is still running on $API (remove it with: docker compose -p $PROJECT down -v)"
  else
    dc down -v --remove-orphans >/dev/null 2>&1
  fi
  rm -rf "$PIECES"
}

# --- API measurements ----------------------------------------------------------------------------

# Every queued job must have been claimed once and completed once: SUCCEEDED, attempt 1, one row.
check_dispatched_once() {  # expected job count -> prints JSON, and fails the run on a violation
  local counts
  counts=$(psql_ -F '|' -c "SELECT count(*), count(*) FILTER (WHERE state = 'SUCCEEDED'), coalesce(max(attempt_count), 0),
    (SELECT count(*) FROM attempts), (SELECT count(*) FROM attempts WHERE status = 'SUCCEEDED') FROM jobs")
  jq -n --arg counts "$counts" --argjson expected "$1" '($counts | split("|") | map(tonumber)) as $c |
    {jobs: $c[0], succeeded: $c[1], maxAttemptCount: $c[2], attempts: $c[3], succeededAttempts: $c[4],
     ok: ($c[0] == $expected and $c[1] == $expected and $c[2] == 1 and $c[3] == $expected and $c[4] == $expected)}'
}

dispatch_run() {  # fake workers, repetition
  local concurrency=$1 rep=$2 setup first before result after invariants
  reset_database
  setup=$(bench setup --warmup-jobs "$WARMUP_JOBS" --jobs "$MEASURED_JOBS")
  first=$(jq '.measuredExperiments[0]' <<<"$setup")
  before=$(cpu_snapshot)
  result=$(bench dispatch --concurrency "$concurrency" --first-measured-experiment "$first")
  after=$(cpu_snapshot)
  invariants=$(check_dispatched_once $((WARMUP_JOBS + MEASURED_JOBS)))
  jq -n --argjson result "$result" --argjson before "$before" --argjson after "$after" \
    --argjson invariants "$invariants" --argjson rep "$rep" \
    '$result + {rep: $rep, invariants: $invariants,
      cpuSeconds: {api: (($after.api - $before.api) / 1e6), postgres: (($after.postgres - $before.postgres) / 1e6)}}' \
    >"$PIECES/dispatch-c$concurrency-r$rep.json"
  say "$(jq -r '"\(.concurrency) fake worker(s): \(.jobsPerSecond // 0 | floor) jobs/s, claim p50 \(.claim.p50Ms // 0 | . * 100 | round / 100) ms, complete p50 \(.complete.p50Ms // 0 | . * 100 | round / 100) ms"' \
    "$PIECES/dispatch-c$concurrency-r$rep.json")"
  if ! jq -e '.invariants.ok and (.errors | length == 0) and .claimed == .completed' \
      "$PIECES/dispatch-c$concurrency-r$rep.json" >/dev/null; then
    fail "dispatch with $concurrency fake worker(s), run $rep: $(jq -c '{invariants, errors, claimed, completed}' "$PIECES/dispatch-c$concurrency-r$rep.json")"
  fi
}

flush_rate() {
  local output
  output=$(dc exec -T -u postgres postgres sh -c \
    '/usr/lib/postgresql/*/bin/pg_test_fsync -s 1 -f /var/lib/postgresql/bench_fsync.tmp' </dev/null 2>&1) \
    || die "pg_test_fsync failed"
  # The fdatasync line of "one 8kB write": the method PostgreSQL uses here (wal_sync_method).
  awk '/one 8kB write/ {section = 1} section && $1 == "fdatasync" {print $2, $4; exit}' <<<"$output" |
    jq -R 'split(" ") | {method: "fdatasync", write: "one 8 kB write", opsPerSecond: (.[0] | tonumber),
                         microsecondsPerOp: (.[1] | tonumber)}' >"$PIECES/flush.json"
  say "one 8 kB write plus fdatasync: $(jq -r '"\(.microsecondsPerOp) µs (\(.opsPerSecond | floor) per second)"' "$PIECES/flush.json")"
}

api_benchmarks() {
  local rep concurrency size first
  header "API: dispatch without training (claim, then complete at once)"
  say "warming up the JVM: 5000 jobs with 8 fake workers, not measured"
  reset_database
  first=$(bench setup --warmup-jobs 0 --jobs 5000 | jq '.measuredExperiments[0]')
  bench dispatch --concurrency 8 --first-measured-experiment "$first" >/dev/null
  for rep in $(seq 1 "$REPS"); do  # all levels once per round, so drift over time affects each alike
    say "round $rep of $REPS"
    for concurrency in "${CONCURRENCY[@]}"; do
      dispatch_run "$concurrency" "$rep"
    done
  done

  header "API: submission (POST /experiments with an Idempotency-Key)"
  for rep in $(seq 1 "$REPS"); do
    for size in "${SUBMIT_SIZES[@]}"; do
      reset_database
      bench submit --size "$size" --requests "$SUBMIT_REQUESTS" --warmup 3 |
        jq --argjson rep "$rep" '. + {rep: $rep}' >"$PIECES/submit-s$size-r$rep.json"
      say "$(jq -r '"\(.size) job(s) per request: p50 \(.latency.p50Ms * 100 | round / 100) ms"' "$PIECES/submit-s$size-r$rep.json")"
    done
  done
  reset_database

  header "API: HTTP without the database (GET /actuator), for reference"
  for rep in $(seq 1 "$REPS"); do
    for concurrency in "${BASELINE_CONCURRENCY[@]}"; do
      bench baseline --concurrency "$concurrency" --requests "$BASELINE_REQUESTS" |
        jq --argjson rep "$rep" '. + {rep: $rep}' >"$PIECES/baseline-c$concurrency-r$rep.json"
      say "$(jq -r '"\(.concurrency) client(s): \(.requestsPerSecond | floor) requests/s, p50 \(.latency.p50Ms * 100 | round / 100) ms"' "$PIECES/baseline-c$concurrency-r$rep.json")"
    done
  done

  header "PostgreSQL: the disk's flush rate (pg_test_fsync), for reference"
  flush_rate
  [ -s "$PIECES/flush.json" ] || die "could not read pg_test_fsync's fdatasync result"
}

# --- Training measurements -----------------------------------------------------------------------

# Waits until every running worker container has logged worker.started, i.e. is polling.
wait_for_workers() {  # count
  local deadline=$((SECONDS + 120)) container ready
  while ((SECONDS < deadline)); do
    ready=0
    for container in $(dc ps -q worker); do
      docker logs "$container" 2>&1 | jq -e -R 'fromjson? | select(.event.action == "worker.started")' >/dev/null && ready=$((ready + 1))
    done
    ((ready >= $1)) && return 0
    sleep 0.5
  done
  die "only $ready of $1 workers started polling within 120 s"
}

training_run() {  # workers, repetition
  local workers=$1 rep=$2 before result after
  local container limits threads
  dc up -d --wait --no-recreate --scale worker="$workers" worker >/dev/null 2>&1 || die "could not scale to $workers workers"
  wait_for_workers "$workers"
  # The limits each worker actually runs with: Docker's CPU quota, and the torch threads it reported.
  limits=$(for container in $(dc ps -q worker); do cpu_limit "$container"; done | jq -s .)
  threads=$(for container in $(dc ps -q worker); do
    docker logs "$container" 2>&1 | jq -R 'fromjson? | select(.event.action == "worker.configured") | .torchThreads'
  done | jq -s .)
  reset_database
  before=$(cpu_snapshot)
  result=$(bench train --warmup-jobs $((10 * workers)))
  after=$(cpu_snapshot)
  jq -n --argjson result "$result" --argjson before "$before" --argjson after "$after" \
    --argjson workers "$workers" --argjson rep "$rep" --argjson limits "$limits" --argjson threads "$threads" \
    '$result + {workerCount: $workers, rep: $rep, workerCpuLimits: $limits, workerTorchThreads: $threads,
      cpuSeconds: {api: (($after.api - $before.api) / 1e6), postgres: (($after.postgres - $before.postgres) / 1e6),
                   workers: (($after.workers - $before.workers) / 1e6)}}' >"$PIECES/training-w$workers-r$rep.json"
  say "$(jq -r '"\(.workerCount) worker(s): \(.jobs) jobs in \(.windowSeconds * 10 | round / 10) s, \(.jobsPerSecond * 10 | round / 10) jobs/s"' \
    "$PIECES/training-w$workers-r$rep.json")"
  if ! jq -e '(.notSucceededOnce | length == 0) and (.workers | length) == .workerCount' "$PIECES/training-w$workers-r$rep.json" >/dev/null; then
    fail "training with $workers worker(s), run $rep: $(jq -c '{notSucceededOnce, workers}' "$PIECES/training-w$workers-r$rep.json")"
  fi
}

training_benchmarks() {
  local rep workers
  header "Training: the 200-job grid on real workers (1 CPU and 1 torch thread each)"
  for rep in $(seq 1 "$REPS"); do
    say "round $rep of $REPS"
    for workers in "${WORKERS[@]}"; do
      training_run "$workers" "$rep"
    done
  done
  dc up -d --scale worker=0 worker >/dev/null 2>&1
}

# --- Main ----------------------------------------------------------------------------------------

if [ $# -eq 0 ]; then
  set -- api training
fi
for part in "$@"; do
  case "$part" in
    api | training) ;;
    *) die "unknown part '$part' (choose from: api training)" ;;
  esac
done

trap stop_stack EXIT
start_stack
for part in "$@"; do
  "${part}_benchmarks"
done

header "Report"
api_connections_json >"$PIECES/connections.json" || die "could not count the API's database connections"
mkdir -p docs/benchmarks
python3 scripts/bench/report.py build "$PIECES" "docs/benchmarks/$RUN_ID" ${PUBLISH:+--publish docs/BENCHMARKS.md} \
  || die "could not write the report"
say "wrote docs/benchmarks/$RUN_ID.md and .json"
if ((FAILURES > 0)); then
  say "$FAILURES check(s) FAILED: these numbers describe a broken run"
  exit 1
fi
say "done"
