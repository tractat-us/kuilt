#!/usr/bin/env python3
"""Per-module test-task durations from Gradle's JUnit XML, as a Markdown table (#3115).

    python3 .github/scripts/test-durations.py [root] [--warn-minutes N] [--summary FILE]

Reads every `<module>/build/test-results/<task>/TEST-*.xml` under `root` (default `.`). It prints
one row per (module, task): tests, failures, skipped, the summed suite time, and the wall time from
the first suite's start to the last suite's end. The table goes to stdout, and with `--summary` it
is also appended to that file (CI passes $GITHUB_STEP_SUMMARY). Warnings go to stdout only, because
the runner reads workflow commands from the log and not from the summary.

Wall time, not summed time, is what a job's `timeout-minutes` pays for. On Kotlin/Native the two
are close, because one test binary runs its suites one after another. A task whose wall time is
over `--warn-minutes` (default 40) also gets a `::warning::` annotation. Between Nov 13 and Nov 15
one module's tests grew from 0 to 32 minutes per Apple target, and nothing said so until the job
hit its timeout. This table is what would have said so.

The default was 15 until that module settled at 16-32 minutes a target and warned every night, so
the warning carried no news. At 40 it sits above the slowest task the nightly has run and still
fires with roughly half of the `apple` job's 90 minutes to spare; the workflow says why.

A `0m 00s` row is not evidence that a task was quick. Kotlin/Native on macOS can report
`time="0.0"` for a run that provably did its work (AGENTS.md, on `-P`-gated probes), so only a
large number here means anything.

It never fails the step. A missing or unreadable XML is skipped: the test task's own result is the
verdict, and this is only the report.
"""
import argparse
import collections
import datetime as dt
import glob
import os
import re

parser = argparse.ArgumentParser()
parser.add_argument("root", nargs="?", default=".")
parser.add_argument("--warn-minutes", type=float, default=40.0)
parser.add_argument("--summary", help="file to append the Markdown table to")
args = parser.parse_args()

SUITE = re.compile(r"<testsuite\b[^>]*>")


def attr(tag, name):
    m = re.search(r"\b" + name + r'="([^"]*)"', tag)
    return m.group(1) if m else None


def parse_ts(value):
    if not value:
        return None
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)
    except ValueError:
        return None


# (module, task) -> [tests, failures, skipped, summed seconds, first start, last end]
rows = collections.defaultdict(lambda: [0, 0, 0, 0.0, None, None])
pattern = os.path.join(args.root, "**", "build", "test-results", "*", "TEST-*.xml")
for path in glob.glob(pattern, recursive=True):
    if "node_modules" in path:
        continue
    parts = path.split(os.sep)
    i = len(parts) - 1 - parts[::-1].index("test-results")
    module, task = parts[i - 2], parts[i + 1]
    try:
        with open(path, errors="replace") as f:
            head = f.read(4000)
    except OSError:
        continue
    m = SUITE.search(head)
    if not m:
        continue
    tag = m.group(0)
    try:
        seconds = float(attr(tag, "time") or 0)
        tests = int(attr(tag, "tests") or 0)
        failed = int(attr(tag, "failures") or 0) + int(attr(tag, "errors") or 0)
        skipped = int(attr(tag, "skipped") or 0)
    except ValueError:
        continue
    row = rows[(module, task)]
    row[0] += tests
    row[1] += failed
    row[2] += skipped
    row[3] += seconds
    start = parse_ts(attr(tag, "timestamp"))
    if start is not None:
        end = start + dt.timedelta(seconds=seconds)
        row[4] = start if row[4] is None or start < row[4] else row[4]
        row[5] = end if row[5] is None or end > row[5] else row[5]


def wall(row):
    if row[4] is None or row[5] is None:
        return row[3]
    return (row[5] - row[4]).total_seconds()


def fmt(seconds):
    return f"{int(seconds // 60)}m {int(seconds % 60):02d}s"


ordered = sorted(rows.items(), key=lambda kv: -wall(kv[1]))
lines = ["### Test-task durations", ""]
if not rows:
    lines.append("No test results found.")
else:
    lines.append("| module | task | tests | failed | skipped | wall | summed |")
    lines.append("|---|---|---:|---:|---:|---:|---:|")
    for (module, task), row in ordered:
        lines.append(
            f"| {module} | {task} | {row[0]} | {row[1]} | {row[2]} | {fmt(wall(row))} | {fmt(row[3])} |"
        )
    total = sum(row[3] for row in rows.values())
    lines.append("")
    lines.append(
        f"Summed over every task: {fmt(total)}. "
        "Wall runs from a task's first suite start to its last suite end."
    )
table = "\n".join(lines) + "\n"
print(table)
if args.summary:
    try:
        with open(args.summary, "a") as f:
            f.write(table + "\n")
    except OSError as e:
        print(f"test-durations: could not append to {args.summary}: {e}")

limit = args.warn_minutes * 60
for (module, task), row in ordered:
    if wall(row) > limit:
        print(
            f"::warning title=Slow test task::{module}:{task} took {fmt(wall(row))} of wall time, "
            f"over the {args.warn_minutes:g}-minute budget per test task (#3115)."
        )
