#!/usr/bin/env python3
"""The client side of scripts/benchmark.sh. It talks to the API over HTTP only, as workers and
researchers do, and prints one JSON object with its measurements to stdout.

It runs inside the benchmark's Compose network, in the worker's base image, using only the standard
library. Commands:

  setup --warmup-jobs W --jobs M
      queue W warm-up jobs, then M measured jobs (experiments of at most 500 jobs each)
  dispatch --concurrency C --first-measured-experiment E
      C fake workers each loop claim -> complete, with fixed metrics and no training, until the
      queue is empty. Only jobs of experiments >= E are measured. Each fake worker is a process of
      its own, like a real worker, so the client is not limited by one interpreter's lock.
  submit --size N --requests K --warmup W
      one client posts experiments of N jobs, each with a fresh Idempotency-Key
  baseline --concurrency C --requests K
      C processes call GET /actuator: the HTTP stack and this client without the database
  train --warmup-jobs W
      queue W warm-up jobs and the 200-job grid of scripts/make_sweep.py, wait until both are done,
      and summarize the grid's jobs

The load is closed-loop: each fake worker has one request in flight and sends the next as soon as
the last one returns. The latencies are service times at that concurrency, not response times at
a fixed arrival rate.
"""

import argparse
import http.client
import json
import math
import multiprocessing
import queue
import statistics
import subprocess
import sys
import time
import uuid
from collections import Counter
from datetime import datetime
from urllib.parse import urlsplit

API = "http://api:8080"
MAX_JOBS_PER_EXPERIMENT = 500
RESULT_TIMEOUT_SECONDS = 900
CONFIG = {"learningRate": 0.01, "hiddenUnits": 32, "hiddenLayers": 2, "batchSize": 64, "epochs": 20,
          "optimizer": "adam", "weightDecay": 0.0}
METRICS = {"valAccuracy": 0.5, "valLoss": 1.0, "trainLoss": 1.0, "trainingSeconds": 0.001}


class Client:
    """One keep-alive HTTP connection. http.client reconnects by itself when the server closes it
    (Tomcat does after 100 requests), so reconnects are part of the measured cost, as for workers."""

    def __init__(self, url: str):
        parts = urlsplit(url)
        self._connection = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=60)

    def request(self, method: str, path: str, body: object = None,
                headers: dict[str, str] | None = None) -> tuple[int, bytes, int]:
        """Returns (status, body, nanoseconds). The timer covers sending and the whole response;
        encoding the request body happens before it starts."""
        data = None if body is None else json.dumps(body).encode()
        all_headers = {"Content-Type": "application/json"} if data is not None else {}
        all_headers.update(headers or {})
        start = time.perf_counter_ns()
        self._connection.request(method, path, body=data, headers=all_headers)
        response = self._connection.getresponse()
        payload = response.read()
        return response.status, payload, time.perf_counter_ns() - start

    def json(self, method: str, path: str, body: object = None, *, expect: int = 200,
             headers: dict[str, str] | None = None) -> dict:
        status, payload, _ = self.request(method, path, body, headers)
        if status != expect:
            raise RuntimeError(f"{method} {path}: expected {expect}, got {status}: {payload[:300]!r}")
        return json.loads(payload)


def summarize(nanoseconds: list[int]) -> dict | None:
    """Latency summary in milliseconds. Percentiles are nearest-rank on the sorted samples."""
    if not nanoseconds:
        return None
    ordered = sorted(nanoseconds)

    def rank(q: float) -> float:
        return ordered[max(0, math.ceil(q * len(ordered)) - 1)] / 1e6

    return {"count": len(ordered), "meanMs": statistics.fmean(ordered) / 1e6, "p50Ms": rank(0.50),
            "p90Ms": rank(0.90), "p99Ms": rank(0.99), "maxMs": ordered[-1] / 1e6}


def queue_jobs(client: Client, name: str, count: int) -> list[int]:
    ids = []
    for batch, first in enumerate(range(0, count, MAX_JOBS_PER_EXPERIMENT)):
        size = min(MAX_JOBS_PER_EXPERIMENT, count - first)
        body = {"name": f"{name}-{batch}", "task": "synthetic-mlp-v1", "maxAttempts": 1,
                "jobs": [{"seed": seed, "config": CONFIG} for seed in range(size)]}
        ids.append(client.json("POST", "/experiments", body, expect=201)["id"])
    return ids


def setup(args: argparse.Namespace) -> dict:
    client = Client(args.api)
    warmup = queue_jobs(client, "bench-warmup", args.warmup_jobs)
    measured = queue_jobs(client, "bench-measured", args.jobs)
    return {"warmupExperiments": warmup, "measuredExperiments": measured,
            "warmupJobs": args.warmup_jobs, "measuredJobs": args.jobs}


def dispatch_worker(api: str, first_measured: int, index: int, start, out) -> None:
    """One fake worker process. It puts its measurements on the `out` queue, even if it fails."""
    cpu_before = time.process_time()
    claims, completes, errors = [], [], Counter()
    claimed = completed = 0
    first_start = last_end = None
    try:
        start.wait()
        claimed, completed, first_start, last_end = dispatch_loop(
            Client(api), f"bench-{index}", first_measured, claims, completes, errors)
    except Exception as exc:  # recorded, so the run fails its checks instead of hanging
        errors[f"crash {type(exc).__name__}: {exc}"] += 1
    out.put({"claims": claims, "completes": completes, "claimed": claimed, "completed": completed,
             "errors": errors, "firstStart": first_start, "lastEnd": last_end,
             "cpuSeconds": time.process_time() - cpu_before})


def dispatch_loop(client: Client, worker_id: str, first_measured: int, claims: list[int], completes: list[int],
                  errors: Counter) -> tuple[int, int, int | None, int | None]:
    """Claims and completes jobs until the queue is empty or a request fails."""
    claimed = completed = 0
    first_start = last_end = None
    while True:
        began = time.perf_counter_ns()
        try:
            status, payload, claim_ns = client.request("POST", "/worker/jobs/claim", {"workerId": worker_id})
        except (OSError, http.client.HTTPException) as exc:
            errors[f"claim {type(exc).__name__}"] += 1
            break
        if status == 204:  # the queue is empty: this fake worker is done
            break
        if status != 200:
            errors[f"claim {status}"] += 1
            break
        job = json.loads(payload)
        claimed += 1
        try:
            status, _, complete_ns = client.request(
                "POST", f"/worker/jobs/{job['jobId']}/complete", {"attemptId": job["attemptId"], "metrics": METRICS})
        except (OSError, http.client.HTTPException) as exc:
            errors[f"complete {type(exc).__name__}"] += 1
            break
        ended = time.perf_counter_ns()
        if status != 200:
            errors[f"complete {status}"] += 1
            break
        completed += 1
        if job["experimentId"] >= first_measured:
            claims.append(claim_ns)
            completes.append(complete_ns)
            first_start = began if first_start is None else min(first_start, began)
            last_end = ended if last_end is None else max(last_end, ended)
    return claimed, completed, first_start, last_end


def run_processes(target, count: int, *args) -> tuple[list[dict], float]:
    """Starts `count` processes of target(*args, index, start, out) at once; returns their results
    and the wall-clock seconds from the common start until the last one finished. perf_counter is
    the system-wide monotonic clock on Linux, so timestamps compare across the processes."""
    context = multiprocessing.get_context("forkserver")
    start = context.Barrier(count + 1)
    out = context.Queue()
    processes = [context.Process(target=target, args=(*args, i, start, out)) for i in range(count)]
    for process in processes:
        process.start()
    start.wait()
    began = time.perf_counter_ns()
    try:  # drain before joining, so no child blocks on a full pipe
        results = [out.get(timeout=RESULT_TIMEOUT_SECONDS) for _ in processes]
    except queue.Empty:
        for process in processes:
            process.terminate()
        raise RuntimeError(f"a client process sent no result within {RESULT_TIMEOUT_SECONDS} s")
    wall_seconds = (time.perf_counter_ns() - began) / 1e9
    for process in processes:
        process.join()
    return results, wall_seconds


def dispatch(args: argparse.Namespace) -> dict:
    per_worker, wall_seconds = run_processes(dispatch_worker, args.concurrency, args.api, args.first_measured_experiment)
    cpu_seconds = sum(worker["cpuSeconds"] for worker in per_worker)

    claims = [ns for worker in per_worker for ns in worker["claims"]]
    completes = [ns for worker in per_worker for ns in worker["completes"]]
    errors = sum((worker["errors"] for worker in per_worker), Counter())
    starts = [worker["firstStart"] for worker in per_worker if worker["firstStart"] is not None]
    ends = [worker["lastEnd"] for worker in per_worker if worker["lastEnd"] is not None]
    window = (max(ends) - min(starts)) / 1e9 if starts else None
    return {
        "concurrency": args.concurrency,
        "claimed": sum(worker["claimed"] for worker in per_worker),
        "completed": sum(worker["completed"] for worker in per_worker),
        "measuredJobs": len(claims),
        "measuredSeconds": window,
        "jobsPerSecond": len(claims) / window if window else None,
        "claim": summarize(claims),
        "complete": summarize(completes),
        "errors": dict(errors),
        "wallSeconds": wall_seconds,
        "clientCpuSeconds": cpu_seconds,
        "clientCores": cpu_seconds / wall_seconds,
    }


def submit(args: argparse.Namespace) -> dict:
    client = Client(args.api)
    samples = []
    for i in range(args.warmup + args.requests):
        body = {"name": f"bench-submit-{args.size}-{i}", "task": "synthetic-mlp-v1",
                "jobs": [{"seed": seed, "config": CONFIG} for seed in range(args.size)]}
        status, payload, took = client.request("POST", "/experiments", body,
                                               headers={"Idempotency-Key": f"bench-{uuid.uuid4()}"})
        if status != 201:
            raise RuntimeError(f"POST /experiments: expected 201, got {status}: {payload[:300]!r}")
        if i >= args.warmup:
            samples.append(took)
    return {"size": args.size, "requests": args.requests, "warmup": args.warmup, "latency": summarize(samples)}


def baseline_client(api: str, requests: int, index: int, start, out) -> None:
    cpu_before = time.process_time()
    samples, errors = [], Counter()
    try:
        client = Client(api)
        for _ in range(20):  # connection setup and JIT, not recorded
            client.request("GET", "/actuator")
        start.wait()
        for _ in range(requests):
            status, _, took = client.request("GET", "/actuator")
            if status != 200:
                errors[str(status)] += 1
            samples.append(took)
    except Exception as exc:  # recorded, so the run shows it instead of hanging
        errors[f"crash {type(exc).__name__}: {exc}"] += 1
    out.put({"samples": samples, "errors": errors, "cpuSeconds": time.process_time() - cpu_before})


def baseline(args: argparse.Namespace) -> dict:
    per_client, wall_seconds = run_processes(baseline_client, args.concurrency, args.api, args.requests)
    everything = [ns for client in per_client for ns in client["samples"]]
    return {"concurrency": args.concurrency, "requests": len(everything), "requestsPerSecond": len(everything) / wall_seconds,
            "latency": summarize(everything), "errors": dict(sum((c["errors"] for c in per_client), Counter())),
            "clientCores": sum(client["cpuSeconds"] for client in per_client) / wall_seconds}


def parse_instant(text: str) -> datetime:
    # PostgreSQL keeps microseconds; trim anything finer so fromisoformat accepts it.
    head, _, rest = text.rstrip("Z").partition(".")
    return datetime.fromisoformat(f"{head}.{(rest + '000000')[:6]}+00:00")


def train(args: argparse.Namespace) -> dict:
    client = Client(args.api)
    grid = json.loads(subprocess.run([sys.executable, "/scripts/make_sweep.py", "--seeds", "2", "--name", "bench-train"],
                                     capture_output=True, check=True, text=True).stdout)
    warmup = dict(grid, name="bench-train-warmup", jobs=grid["jobs"][:args.warmup_jobs])
    warmup_id = client.json("POST", "/experiments", warmup, expect=201)["id"]
    measured_id = client.json("POST", "/experiments", grid, expect=201)["id"]
    deadline = time.monotonic() + args.timeout
    while True:
        progress = client.json("GET", f"/experiments/{measured_id}")["progress"]
        if progress["queued"] + progress["running"] == 0:
            break
        if time.monotonic() > deadline:
            raise RuntimeError(f"experiment {measured_id} not finished after {args.timeout} s: {progress}")
        time.sleep(0.2)

    jobs = client.json("GET", f"/experiments/{measured_id}/jobs")["jobs"]
    invalid = [job["id"] for job in jobs if job["state"] != "SUCCEEDED" or job["attemptCount"] != 1]
    starts = [parse_instant(job["startedAt"]) for job in jobs]
    ends = [parse_instant(job["finishedAt"]) for job in jobs]
    window = (max(ends) - min(starts)).total_seconds()
    training = [job["result"]["trainingSeconds"] for job in jobs]
    return {"warmupExperiment": warmup_id, "measuredExperiment": measured_id, "warmupJobs": len(warmup["jobs"]),
            "jobs": len(jobs), "notSucceededOnce": invalid, "windowSeconds": window, "jobsPerSecond": len(jobs) / window,
            "trainingSecondsTotal": sum(training),
            "trainingSeconds": {"p50": statistics.median(training), "min": min(training), "max": max(training)},
            "workers": sorted({job["workerId"] for job in jobs})}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api", default=API)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("setup")
    command.add_argument("--warmup-jobs", type=int, required=True)
    command.add_argument("--jobs", type=int, required=True)
    command = commands.add_parser("dispatch")
    command.add_argument("--concurrency", type=int, required=True)
    command.add_argument("--first-measured-experiment", type=int, required=True)
    command = commands.add_parser("submit")
    command.add_argument("--size", type=int, required=True)
    command.add_argument("--requests", type=int, required=True)
    command.add_argument("--warmup", type=int, default=3)
    command = commands.add_parser("baseline")
    command.add_argument("--concurrency", type=int, required=True)
    command.add_argument("--requests", type=int, required=True)
    command = commands.add_parser("train")
    command.add_argument("--warmup-jobs", type=int, required=True)
    command.add_argument("--timeout", type=float, default=900)
    args = parser.parse_args()
    handler = {"setup": setup, "dispatch": dispatch, "submit": submit, "baseline": baseline, "train": train}
    json.dump(handler[args.command](args), sys.stdout)
    print()


if __name__ == "__main__":
    main()
