#!/usr/bin/env bash
# End-to-end demonstration of the scheduler under failure, with every claim checked.
#
#   scripts/demo.sh                 the whole story, about two minutes:
#                                     1. a 200-configuration sweep shared by several workers, and its ranking
#                                     2. crash recovery: SIGKILL a worker in the middle of training
#                                     3. a stale worker: freeze one past its lease, then let it try to report
#   scripts/demo.sh crash stale     only the named scenarios (sweep, crash, stale)
#
# It runs in its own Compose project (mlsched-demo, API on localhost:18080) with 10-second leases,
# so it never touches your main stack or its data. Everything is removed afterwards unless KEEP=1.
# The exit status is non-zero if any check fails.
set -uo pipefail

cd "$(dirname "$0")/.."

PROJECT=mlsched-demo
export API_PORT="${DEMO_API_PORT:-18080}" POSTGRES_PORT="${DEMO_POSTGRES_PORT:-15432}"
API="http://localhost:$API_PORT"
WORKERS=3
# About ten seconds of training in a 1-CPU container: long enough to interrupt on purpose.
LONG_CONFIG='{"learningRate":0.01,"hiddenUnits":256,"hiddenLayers":4,"batchSize":8,"epochs":50,"optimizer":"adam","weightDecay":0.0}'
FAKE_METRICS='{"valAccuracy":0.01,"valLoss":9.9,"trainLoss":9.9,"trainingSeconds":1.0}'

FAILURES=0
START=$SECONDS

# --- Output and checks -------------------------------------------------------------------------

say()    { printf '%4ss  %s\n' "$((SECONDS - START))" "$*"; }
header() { printf '\n== %s\n' "$*"; }
pass()   { printf '       [ok]   %s\n' "$*"; }
fail()   { printf '       [FAIL] %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
die()    { printf 'demo: %s\n' "$*" >&2; exit 2; }

# check "claim" command...: runs the command quietly; its exit status decides pass or fail.
check() {
  local claim=$1
  shift
  if "$@" >/dev/null 2>&1; then pass "$claim"; else fail "$claim"; fi
}

# --- The isolated stack ------------------------------------------------------------------------

dc()   { docker compose -p "$PROJECT" -f compose.yaml -f scripts/demo/compose.demo.yaml "$@"; }
get()  { curl -sS "$API$1"; }
post() { local path=$1; shift; curl -sS -X POST "$API$path" -H 'Content-Type: application/json' "$@"; }

# Log events: the JSON lines of the API's or one container's log, one object per line. Anything
# else on the stream (a stop signal's notice, say) is skipped. docs/DESIGN.md lists the fields.
api_events()       { dc logs --no-log-prefix api 2>/dev/null | jq -c -R 'fromjson? // empty'; }
container_events() { docker logs "$1" 2>&1 | jq -c -R 'fromjson? // empty'; }

start_stack() {
  local tool output policy message
  for tool in docker curl jq python3; do
    command -v "$tool" >/dev/null || die "$tool is required"
  done
  if curl -s -o /dev/null "$API/actuator/health"; then
    die "something already answers on $API (set DEMO_API_PORT to use another port)"
  fi
  say "starting $PROJECT: PostgreSQL, the API on $API, $WORKERS workers (the first run builds images)"
  # Quiet on success. On failure, show why: a build error never reaches the containers' logs.
  if ! output=$(dc up -d --build --wait --scale worker="$WORKERS" 2>&1); then
    printf '%s\n' "$output" | tail -n 20 >&2
    die "the stack did not start; see: docker compose -p $PROJECT logs"
  fi
  policy=$(api_events | jq -c 'select(.event.action == "recovery.policy")' | head -1)
  message=$(jq -r .message <<<"$policy" 2>/dev/null)
  say "API: ${message:-no recovery.policy event found}"
  check "the demo's short leases are in effect (10 s lease, 3 s heartbeats, 1 s sweeps)" \
    jq -e '.leaseSeconds == 10 and .heartbeatIntervalSeconds == 3 and .sweepIntervalSeconds == 1' <<<"$policy"
}

stop_stack() {
  if [ "${KEEP:-0}" = 1 ]; then
    say "KEEP=1: $PROJECT is still running on $API (remove it with: docker compose -p $PROJECT down -v)"
  else
    dc down -v --remove-orphans >/dev/null 2>&1
  fi
}

# --- Jobs and workers --------------------------------------------------------------------------

submit_long() {  # experiment name -> experiment id
  post /experiments -d "{\"name\":\"$1\",\"task\":\"synthetic-mlp-v1\",\"maxAttempts\":3,
    \"jobs\":[{\"seed\":0,\"config\":$LONG_CONFIG}]}" | jq -r .id
}

only_job()    { get "/experiments/$1/jobs" | jq -r '.jobs[0].id'; }
field()       { get "/jobs/$1" | jq -r "$2"; }
status_line() { get "/jobs/$1" | jq -r '"\(.state) attempt=\(.attemptCount) worker=\(.workerId // "-")"'; }

# Prints every change of the job's status until it matches a regex. Fails the demo on timeout.
follow() {  # job id, timeout in seconds, regex
  local job=$1 deadline=$((SECONDS + $2)) regex=$3 last="" now
  while ((SECONDS < deadline)); do
    now=$(status_line "$job")
    if [ "$now" != "$last" ]; then
      say "job $job: $now"
      last=$now
    fi
    if [[ $now =~ $regex ]]; then
      return 0
    fi
    sleep 0.5
  done
  fail "job $job did not reach /$regex/ within $2 s"
  return 1
}

# A worker id is "<hostname>-<pid>", and a container's hostname is its short id.
container_of() {  # worker id -> container name
  docker ps --filter "label=com.docker.compose.project=$PROJECT" --filter label=com.docker.compose.service=worker \
    --format '{{.ID}} {{.Names}}' | awk -v host="${1%-*}" 'index($1, host) == 1 {print $2}'
}

print_attempts() {  # job id
  get "/jobs/$1/attempts" | jq -r '.attempts[] |
    "       attempt \(.attemptNumber): \(.status) on \(.workerId)\(if .errorType then " (" + .errorType + ")" else "" end)"'
}

# Whether the container's log has an accepted result for the job's attempt with this number.
logged_result() {  # container, job id, attempt number
  container_events "$1" | jq -e --argjson job "$2" --argjson n "$3" \
    'select(.event.action == "result.accepted" and .jobId == $job and .attemptNumber == $n)' >/dev/null
}

# The frozen worker logged no result for attempt 1, while the same query finds attempt 2's in its
# worker's log. Without that second half, a changed log format would pass this check unnoticed.
reported_only_by_attempt_2() {  # frozen container, replacement's container, job id
  ! logged_result "$1" "$3" 1 && logged_result "$2" "$3" 2
}

# Every log event about one job, from the API and all workers, in time order. API events carry
# attempt ids; the claim events map them to attempt numbers.
print_timeline() {  # job id
  dc logs --no-log-prefix api worker 2>/dev/null | jq -n -r -R --argjson job "$1" '
    def ts: ."@timestamp" | capture("^(?<s>[^.Z]+)(\\.(?<f>[0-9]+))?Z$") | .s + "." + ((.f // "") + "000000000")[0:9];
    def pad($n): . + (" " * $n) | .[0:$n];
    [inputs | fromjson? | select(.jobId == $job)] as $events
    | ($events | map(select(.attemptNumber) | {key: .attemptId, value: .attemptNumber}) | from_entries) as $number
    | $events | sort_by(ts)[]
    | (if .service.name == "scheduler-api" then "api" else "worker \(.workerId)" end) as $source
    | "       \(ts[11:23])  \($source | pad(23))\(.event.action | pad(20))attempt \($number[.attemptId // ""] // "-")"'
}

per_worker() {  # experiment id -> "workers: a1b2=67 c3d4=66 ..."
  get "/experiments/$1/jobs" | jq -r '[.jobs[] | select(.workerId) | .workerId[0:4]]
    | group_by(.) | map("\(.[0])=\(length)") | "workers: " + join(" ")'
}

# --- Scenarios ---------------------------------------------------------------------------------

sweep() {
  header "1. A 200-configuration sweep, shared by $WORKERS workers"
  local body key exp again progress running peak=0 deadline last_print=-10
  body=$(python3 scripts/make_sweep.py --name demo-sweep --seeds 2)
  key="demo-sweep-$RANDOM"
  exp=$(post /experiments -H "Idempotency-Key: $key" -d "$body" | jq -r .id)
  say "submitted experiment $exp: $(get "/experiments/$exp" | jq .progress.total) jobs (100 configs x 2 seeds), Idempotency-Key $key"
  again=$(post /experiments -H "Idempotency-Key: $key" -d "$body" | jq -r .id)
  say "the same submission again, as if the first response had been lost: experiment $again"
  check "resubmitting with the same Idempotency-Key created nothing new" test "$again" = "$exp"

  deadline=$((SECONDS + 300))  # a hang guard, not a speed check: a 2-CPU CI runner needs the headroom
  while ((SECONDS < deadline)); do
    progress=$(get "/experiments/$exp" | jq -c .progress)
    running=$(jq .running <<<"$progress")
    ((running > peak)) && peak=$running
    if ((SECONDS - last_print >= 2)); then
      say "$(jq -r '"queued=\(.queued) running=\(.running) succeeded=\(.succeeded) failed=\(.failed)"' <<<"$progress")   $(per_worker "$exp")"
      last_print=$SECONDS
    fi
    [ "$(jq '.queued + .running' <<<"$progress")" = 0 ] && break
    sleep 0.5
  done
  say "done: $(jq -r '"succeeded=\(.succeeded) failed=\(.failed)"' <<<"$progress")   $(per_worker "$exp")"
  check "all 200 jobs succeeded" test "$(jq .succeeded <<<"$progress")" = 200
  check "workers ran jobs at the same time (peak running: $peak)" test "$peak" -ge 2
  check "every worker took part" test "$(get "/experiments/$exp/jobs" | jq '[.jobs[].workerId] | unique | length')" = "$WORKERS"

  say "best configurations (GET /experiments/$exp/best?limit=5):"
  get "/experiments/$exp/best?limit=5" | jq -r '.jobs[] |
    "       #\(.rank)  valAccuracy=\(.valAccuracy)  \(.config.optimizer) lr=\(.config.learningRate) \(.config.hiddenUnits)x\(.config.hiddenLayers) seed=\(.seed)"'
  check "the ranking is best-first" jq -e '[.jobs[].valAccuracy] | . == (sort | reverse)' \
    <<<"$(get "/experiments/$exp/best?limit=100")"
}

crash() {
  header "2. Crash recovery: SIGKILL a worker in the middle of training"
  local exp job worker victim history
  exp=$(submit_long crash-demo)
  job=$(only_job "$exp")
  follow "$job" 30 '^RUNNING' || return
  worker=$(field "$job" .workerId)
  victim=$(container_of "$worker")
  sleep 2
  docker kill -s KILL "$victim" >/dev/null
  say "SIGKILL $victim while it trains job $job: no cleanup, and nothing renews its lease any more"
  follow "$job" 120 '^(SUCCEEDED|FAILED)' || return
  print_attempts "$job"
  history=$(get "/jobs/$job/attempts")
  check "the job succeeded despite the crash" test "$(field "$job" .state)" = SUCCEEDED
  check "attempt 1 expired on the killed worker" jq -e --arg w "$worker" \
    '.attempts[0] | .status == "EXPIRED" and .errorType == "LEASE_EXPIRED" and .workerId == $w' <<<"$history"
  check "attempt 2 succeeded on another worker" jq -e --arg w "$worker" \
    '.attempts[1] | .status == "SUCCEEDED" and .workerId != $w' <<<"$history"
  check "exactly one retry was used" test "$(field "$job" .attemptCount)" = 2
  dc up -d --scale worker="$WORKERS" --wait >/dev/null 2>&1  # replace the killed worker
}

stale() {
  header "3. A stale worker: freeze one past its lease, then let it try to report"
  local exp job worker frozen replacement stale_attempt response before fenced="" deadline
  exp=$(submit_long stale-demo)
  job=$(only_job "$exp")
  follow "$job" 30 '^RUNNING' || return
  worker=$(field "$job" .workerId)
  frozen=$(container_of "$worker")
  # The API never hands attempt ids out through read endpoints; an operator finds them in its log.
  stale_attempt=$(api_events | jq -r --argjson job "$job" \
    'select(.event.action == "job.claimed" and .jobId == $job and .attemptNumber == 1) | .attemptId' | head -1)
  sleep 2
  docker pause "$frozen" >/dev/null
  say "docker pause $frozen: alive but frozen mid-training, like a long GC pause or a network partition"
  if ! follow "$job" 60 'attempt=2 '; then
    docker unpause "$frozen" >/dev/null
    return
  fi
  say "attempt 1's lease ran out and another worker took the job; the frozen worker does not know yet"

  # The dangerous moment: the old attempt reports while its replacement is still training.
  response=$(post "/worker/jobs/$job/complete" -d "{\"attemptId\":\"$stale_attempt\",\"metrics\":$FAKE_METRICS}")
  say "attempt 1 reports a fake result now: $(jq -c '{status, code, jobState}' <<<"$response")"
  check "the stale report was rejected while attempt 2 runs" \
    jq -e '.status == 409 and .code == "ATTEMPT_NOT_CURRENT"' <<<"$response"
  check "attempt 2 still holds the job" test "$(status_line "$job" | cut -d' ' -f1-2)" = "RUNNING attempt=2"

  docker unpause "$frozen" >/dev/null
  say "docker unpause $frozen: it resumes attempt 1 and sends its next heartbeat"
  deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)) && [ -z "$fenced" ]; do
    fenced=$(container_events "$frozen" | jq -r --argjson job "$job" \
      'select(.event.action == "lease.lost" and .jobId == $job and .attemptNumber == 1) | .message' | head -1)
    [ -z "$fenced" ] && sleep 0.5
  done
  [ -n "$fenced" ] && say "frozen worker's log: $fenced"
  check "the woken worker learned it had lost the lease and stopped" test -n "$fenced"

  follow "$job" 90 '^(SUCCEEDED|FAILED)' || return
  before=$(get "/jobs/$job" | jq -c '{valAccuracy, result}')
  response=$(post "/worker/jobs/$job/complete" -d "{\"attemptId\":\"$stale_attempt\",\"metrics\":$FAKE_METRICS}")
  say "attempt 1 reports its fake result again, after attempt 2 succeeded: $(jq -c '{status, code, jobState}' <<<"$response")"
  print_attempts "$job"
  check "the late stale report was rejected" jq -e '.status == 409' <<<"$response"
  check "the accepted result is attempt 2's, unchanged" test "$(get "/jobs/$job" | jq -c '{valAccuracy, result}')" = "$before"
  replacement=$(container_of "$(get "/jobs/$job/attempts" | jq -r '.attempts[1].workerId')")
  check "the frozen worker never reported a result for attempt 1 (the same query finds attempt 2's)" \
    reported_only_by_attempt_2 "$frozen" "$replacement" "$job"
  check "attempt 1 expired and attempt 2 succeeded" jq -e '[.attempts[].status] == ["EXPIRED", "SUCCEEDED"]' \
    <<<"$(get "/jobs/$job/attempts")"
  say "the job's story, from the JSON logs of the API and all $WORKERS workers:"
  print_timeline "$job"
}

# --- Main --------------------------------------------------------------------------------------

if [ $# -eq 0 ]; then
  set -- sweep crash stale
fi
for scenario in "$@"; do
  case "$scenario" in
    sweep | crash | stale) ;;
    *) die "unknown scenario '$scenario' (choose from: sweep crash stale)" ;;
  esac
done

trap stop_stack EXIT
start_stack
for scenario in "$@"; do
  "$scenario"
done

header "Result"
if ((FAILURES == 0)); then
  say "every check passed"
  exit 0
fi
say "$FAILURES check(s) FAILED"
exit 1
