#!/usr/bin/env python3
"""Turns the pieces scripts/benchmark.sh collected into <out>.json (everything, merged) and <out>.md
(tables of medians and ranges). With --publish FILE, it also replaces the part of FILE between the
results markers with the tables. Every number comes from the pieces; nothing is typed in by hand.

Usage:
  report.py build PIECES_DIR OUT_PREFIX [--publish docs/BENCHMARKS.md]
  report.py render RUN.json [--publish docs/BENCHMARKS.md]
      rewrite RUN.md (and the published section) from a run's data, e.g. after a formatting change
  report.py compare RUN.json... [--publish docs/BENCHMARKS.md]
      a table of dispatch throughput and API cost across runs with different settings, published
      between the experiments markers
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
from datetime import datetime, timezone
from pathlib import Path

MARKERS = {"results": ("<!-- results:begin -->", "<!-- results:end -->"),
           "experiments": ("<!-- experiments:begin -->", "<!-- experiments:end -->")}


def load(directory: Path, pattern: str) -> list[dict]:
    return [json.loads(path.read_text()) for path in sorted(directory.glob(pattern))]


def by(runs: list[dict], key: str) -> dict:
    groups: dict = {}
    for run in runs:
        groups.setdefault(run[key], []).append(run)
    return dict(sorted(groups.items()))


def median(values) -> float:
    return statistics.median(list(values))


def spread(values, fmt) -> str:
    """The median, with the range when there is more than one run: 1,234 [1,200–1,260]."""
    values = list(values)
    if len(values) == 1:
        return fmt(values[0])
    return f"{fmt(median(values))} [{fmt(min(values))}–{fmt(max(values))}]"


def count(value: float) -> str:
    return f"{value:,.0f}"


def ms(value: float) -> str:
    if value < 10:
        return f"{value:.2f} ms"
    if value < 100:
        return f"{value:.1f} ms"
    return f"{value:,.0f} ms"


def cores(value: float) -> str:
    return f"{value:.2f}"


def percent(value: float) -> str:
    return f"{value * 100:.0f}%"


def runs_of(n: int) -> str:
    return f"{n} run" if n == 1 else f"{n} runs"


def gib(value: float) -> str:
    return f"{value / 2 ** 30:.1f} GiB"


def table(header: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return "\n".join(lines)


def environment_section(env: dict, flush: dict | None, connections: dict | None) -> str:
    host, docker = env["host"], env["docker"]
    cpu = f"{host['cpu']}, {host['cores']} cores"
    if host.get("performanceCores") is not None:
        cpu += f" ({host['performanceCores']} performance, {host['efficiencyCores']} efficiency)"
    power = ""
    if "power" in host:
        power = f"; power: {host['power']}; low power mode {'on' if host['lowPowerMode'] else 'off'}"
    limits = env["limits"]
    rows = [
        ["Host", f"{cpu}, {gib(host['memoryBytes'])}, {host['os']}{power}; load average {host['loadAverage1m']} at the start"],
        ["Docker", f"{docker['os']} {docker['server']}, Compose {docker['compose']}; {docker['cpus']} CPUs and "
                   f"{gib(docker['memoryBytes'])} for containers, kernel {docker['kernel']}"],
        ["Other containers", f"{env['otherContainers']['running']} running outside the benchmark, using "
                             f"{env['otherContainers']['cpuPercent'] / 100:.2f} CPU cores together when measured at the start"],
        ["Code", f"commit `{env['code']['commit']}`"
                 + (", with **uncommitted changes** to api/, worker/, or compose.yaml"
                    if env["code"].get("systemUnderTestChanged", env["code"].get("uncommittedChanges"))
                    else "; api/, worker/, and compose.yaml as committed")],
        ["PostgreSQL", f"{env['postgres']['version']}; synchronous_commit {env['postgres']['synchronousCommit']}, "
                       f"fsync {env['postgres']['fsync']}, wal_sync_method {env['postgres']['walSyncMethod']}, "
                       f"shared_buffers {env['postgres']['sharedBuffers']}"],
        ["CPU limits", "API: " + ("none" if limits["apiCpus"] is None else f"{limits['apiCpus']:g} CPUs")
                       + "; PostgreSQL: " + ("none" if limits["postgresCpus"] is None else f"{limits['postgresCpus']:g} CPUs")],
    ]
    settings = env.get("settings")
    if settings:
        defaults = settings == {"apiConnectionPool": 10, "apiLogLevel": "INFO"}
        rows.append(["API settings", f"connection pool of {settings['apiConnectionPool']}, log level "
                                     f"{settings['apiLogLevel']} for the application's loggers"
                                     + (" (the stack's defaults)" if defaults else " (**changed for an experiment**)")])
    if flush:
        rows.append(["Disk flush", f"one 8 kB write plus fdatasync took {flush['microsecondsPerOp']} µs "
                                   f"({count(flush['opsPerSecond'])} per second), by pg_test_fsync in the database's volume"])
    if connections:
        rows.append(["API database connections", f"{connections['apiDatabaseConnections']} open at the end of the run"])
    return table(["", ""], rows)


def api_cpu_per_job(run: dict) -> float:
    return run["cpuSeconds"]["api"] * 1000 / run["claimed"]


def dispatch_section(runs: list[dict]) -> str:
    latency, resources = [], []
    for concurrency, group in by(runs, "concurrency").items():
        latency.append([
            str(concurrency),
            spread((run["jobsPerSecond"] for run in group), count),
            ms(median(run["claim"]["p50Ms"] for run in group)),
            ms(median(run["claim"]["p99Ms"] for run in group)),
            ms(median(run["complete"]["p50Ms"] for run in group)),
            ms(median(run["complete"]["p99Ms"] for run in group)),
        ])
        resources.append([
            str(concurrency),
            ms(median(api_cpu_per_job(run) for run in group)),
            ms(median(run["cpuSeconds"]["postgres"] * 1000 / run["claimed"] for run in group)),
            cores(median(run["cpuSeconds"]["api"] / run["wallSeconds"] for run in group)),
            cores(median(run["cpuSeconds"]["postgres"] / run["wallSeconds"] for run in group)),
            cores(median(run["clientCores"] for run in group)),
        ])
    ok = [run for run in runs if run["invariants"]["ok"] and not run["errors"] and run["claimed"] == run["completed"]]
    queued = runs[0]["invariants"]["jobs"]
    checks = (f"Checked after every run: all {len(ok)} of {len(runs)} runs claimed each of their {count(queued)} queued jobs "
              "exactly once and completed it exactly once (SUCCEEDED on attempt 1, with one attempt row), without errors.")
    if len(ok) != len(runs):
        checks = f"**{len(runs) - len(ok)} of {len(runs)} runs FAILED their checks; see the JSON.** " + checks
    return "\n\n".join([
        table(["Fake workers", "Jobs/s", "Claim p50", "Claim p99", "Complete p50", "Complete p99"], latency),
        table(["Fake workers", "API CPU per job", "PostgreSQL CPU per job", "API cores", "PostgreSQL cores",
               "Client cores"], resources),
        checks])


def baseline_section(runs: list[dict]) -> str:
    rows = [[str(concurrency), spread((run["requestsPerSecond"] for run in group), count),
             ms(median(run["latency"]["p50Ms"] for run in group)), ms(median(run["latency"]["p99Ms"] for run in group)),
             cores(median(run["clientCores"] for run in group))]
            for concurrency, group in by(runs, "concurrency").items()]
    return table(["Clients", "Requests/s", "p50", "p99", "Client cores"], rows)


def submit_section(runs: list[dict]) -> str:
    rows = []
    for size, group in by(runs, "size").items():
        p50 = median(run["latency"]["p50Ms"] for run in group)
        rows.append([str(size), ms(p50), ms(median(run["latency"]["p90Ms"] for run in group)),
                     ms(max(run["latency"]["maxMs"] for run in group)), count(size / (p50 / 1000))])
    requests = runs[0]["requests"]
    return "\n\n".join([
        table(["Jobs per request", "p50", "p90", "Max", "Jobs stored per second at p50"], rows),
        f"{requests} requests per size and run, after {runs[0]['warmup']} not measured; p50 and p90 are medians over the runs, "
        "and max is the largest of any run."])


def training_section(runs: list[dict]) -> str:
    groups = by(runs, "workerCount")
    base = median(run["jobsPerSecond"] for run in groups[min(groups)])
    rows = []
    for workers, group in groups.items():
        rate = median(run["jobsPerSecond"] for run in group)
        busy = [run["trainingSecondsTotal"] / (workers * run["windowSeconds"]) for run in group]
        outside = [(workers * run["windowSeconds"] - run["trainingSecondsTotal"]) * 1000 / run["jobs"] for run in group]
        rows.append([str(workers), spread((run["jobsPerSecond"] for run in group), lambda v: f"{v:.1f}"),
                     f"{rate / base:.2f}×", percent(rate / base / (workers / min(groups))), spread(busy, percent),
                     ms(median(outside)), ms(median(run["trainingSeconds"]["p50"] * 1000 for run in group))])
    clean = all(not run["notSucceededOnce"] and len(run["workers"]) == run["workerCount"] for run in runs)
    limits = sorted({limit for run in runs for limit in run["workerCpuLimits"]}, key=str)
    threads = sorted({t for run in runs for t in run["workerTorchThreads"]}, key=str)
    observed = (f"each worker ran with a {limits[0]:g}-CPU limit and {threads[0]} torch thread"
                if len(limits) == 1 and len(threads) == 1 and limits[0] is not None
                else f"workers ran with CPU limits {limits} and torch threads {threads}")
    checks = ("Checked after every run: each job SUCCEEDED on its first attempt, and every worker took part."
              if clean else "**Some runs FAILED their checks; see the JSON.**")
    return "\n\n".join([
        table(["Workers", "Jobs/s", "Speed-up", "Efficiency", "Training loop share", "Outside the loop, per job",
               "Training loop per job (p50)"], rows),
        f"{runs[0]['jobs']} measured jobs per run, after {runs[0]['warmupJobs'] // runs[0]['workerCount']} warm-up jobs per "
        f"worker queued ahead of them. As observed, {observed}. " + checks])


def markdown(run_id: str, merged: dict) -> str:
    env = merged["environment"]
    parts = [f"Run `{run_id}`: {runs_of(env['parameters']['reps'])} of every measurement, interleaved. "
             "Tables show the median over the runs, with the range in brackets. "
             "[docs/BENCHMARKS.md](../BENCHMARKS.md) explains the method and the columns.",
             "### Environment", environment_section(env, merged.get("flush"), merged.get("connections"))]
    if merged["dispatch"]:
        parts += ["### Dispatch without training", dispatch_section(merged["dispatch"])]
    if merged["baseline"]:
        parts += ["### HTTP without the database (`GET /actuator`)", baseline_section(merged["baseline"])]
    if merged["submit"]:
        parts += ["### Submission (`POST /experiments` with an `Idempotency-Key`)", submit_section(merged["submit"])]
    if merged["training"]:
        parts += ["### Training on real workers", training_section(merged["training"])]
    return "\n\n".join(parts) + "\n"


def publish(path: Path, block: str, body: str) -> None:
    begin, end = MARKERS[block]
    text = path.read_text()
    pattern = re.compile(re.escape(begin) + ".*?" + re.escape(end), re.DOTALL)
    if not pattern.search(text):
        raise SystemExit(f"{path} has no {begin} ... {end} section")
    generated = f"{begin}\n<!-- Generated by scripts/bench/report.py; do not edit by hand. -->\n\n{body}\n{end}"
    path.write_text(pattern.sub(lambda _: generated, text))


def build(args: argparse.Namespace) -> None:
    connections = args.pieces / "connections.json"
    flush = args.pieces / "flush.json"
    merged = {
        "runId": args.out.name,
        "finishedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "environment": json.loads((args.pieces / "environment.json").read_text()),
        "connections": json.loads(connections.read_text()) if connections.exists() else None,
        "flush": json.loads(flush.read_text()) if flush.exists() and flush.stat().st_size else None,
        "dispatch": load(args.pieces, "dispatch-*.json"),
        "baseline": load(args.pieces, "baseline-*.json"),
        "submit": load(args.pieces, "submit-*.json"),
        "training": load(args.pieces, "training-*.json"),
    }
    args.out.with_suffix(".json").write_text(json.dumps(merged, indent=1) + "\n")
    write_report(merged, args.out.with_suffix(".json"), args.publish)


def write_report(merged: dict, data: Path, published: Path | None) -> None:
    report = markdown(merged["runId"], merged)
    data.with_suffix(".md").write_text(f"# Benchmark run {merged['runId']}\n\n" + report)
    if published:
        publish(published, "results", f"Raw data: [{data.name}](benchmarks/{data.name}).\n\n"
                + report.replace("[docs/BENCHMARKS.md](../BENCHMARKS.md) explains the method and the columns.",
                                 "The [method](#method) section explains the columns."))


def render(args: argparse.Namespace) -> None:
    write_report(json.loads(args.run.read_text()), args.run, args.publish)


def compare(args: argparse.Namespace) -> None:
    runs = [json.loads(path.read_text()) for path in args.runs]
    levels = sorted({run["concurrency"] for merged in runs for run in merged["dispatch"]})
    rows = []
    for merged, path in zip(runs, args.runs):
        settings = merged["environment"]["settings"]
        groups = by(merged["dispatch"], "concurrency")
        top = groups[max(groups)]
        rows.append([f"[{merged['runId']}](benchmarks/{path.name})", str(settings["apiConnectionPool"]), settings["apiLogLevel"]]
                    + [count(median(run["jobsPerSecond"] for run in groups[level])) if level in groups else "–" for level in levels]
                    + [ms(median(api_cpu_per_job(run) for run in top)),
                       cores(median(run["cpuSeconds"]["api"] / run["wallSeconds"] for run in top))])
    header = (["Run", "Connection pool", "Log level"]
              + [f"Jobs/s, {level} fake worker{'' if level == 1 else 's'}" for level in levels]
              + [f"API CPU per job at {levels[-1]}", f"API cores at {levels[-1]}"])
    body = (table(header, rows) + "\n\nMedians over each run's repetitions of the dispatch measurement; "
            "each run's own report has the ranges, latencies, and environment.")
    if args.publish:
        publish(args.publish, "experiments", body)
    else:
        print(body)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("build")
    command.add_argument("pieces", type=Path)
    command.add_argument("out", type=Path, help="output path without extension, e.g. docs/benchmarks/20260930T080000Z")
    command.add_argument("--publish", type=Path, help="a file whose results section to replace")
    command = commands.add_parser("render")
    command.add_argument("run", type=Path)
    command.add_argument("--publish", type=Path, help="a file whose results section to replace")
    command = commands.add_parser("compare")
    command.add_argument("runs", type=Path, nargs="+")
    command.add_argument("--publish", type=Path, help="a file whose experiments section to replace")
    args = parser.parse_args()
    {"build": build, "render": render, "compare": compare}[args.command](args)


if __name__ == "__main__":
    main()
