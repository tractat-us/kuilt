#!/usr/bin/env python3
"""Fail when a changed version stamp re-ran a task that does not read it (#3464).

Usage: stamp-invariance.py <report.tsv> [allowlist]

The report is written by the root build's `StampProbeRecorder` during the SECOND pass of
`.github/scripts/stamp-invariance.sh`: one `<task path>\t<outcome>\t<reasons>` row per task, where
the reasons are Gradle's own "why did this execute" lines. The first pass is the job's normal build
at the job's normal version; the second runs `stampProbeTargets` at a different `-Pversion`. Every
selected task should then be UP-TO-DATE, because the stamp is the only input that changed.

Each EXECUTED row is classified from Gradle's reasons, never from the task's name:

- allowlisted                  — matches a line of the allowlist (the carrier, `:kuilt-base`).
- never up to date             — "Task has not declared any outputs": it runs on every build, so the
                                 stamp cannot be what re-ran it.
- only its output moved        — every reason is about an OUTPUT property (an archive whose file name
                                 carries the version); no input changed, so nothing upstream did.
- unmeasured                   — "No history is available": the first pass never ran it, so this run
                                 cannot say whether the stamp reached it. That is a selection bug, and
                                 it fails too, so the probe cannot quietly grow into a second build.
- leak                         — anything else.

The check also asserts its own precondition: `:kuilt-base:generateKuiltVersion` must have EXECUTED
because its version input changed. If it did not, the stamp never changed and a clean report means
nothing.
"""
from __future__ import annotations

import fnmatch
import sys
from pathlib import Path

ISSUE = "https://github.com/tractat-us/kuilt/issues/3464"
GENERATOR = ":kuilt-base:generateKuiltVersion"


def load_allowlist(path: Path) -> list[str]:
    patterns = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            patterns.append(line)
    return patterns


def classify(reasons: list[str]) -> str:
    if any("has not declared any outputs" in r for r in reasons):
        return "never up to date"
    if any("No history is available" in r for r in reasons):
        return "unmeasured"
    if reasons and all(r.startswith("Output property") for r in reasons):
        return "only its output moved"
    return "leak"


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    report = Path(argv[1])
    allowlist_path = Path(argv[2]) if len(argv) == 3 else Path(__file__).resolve().parents[1] / "stamp-invariance-allowlist.txt"
    allow = load_allowlist(allowlist_path)

    rows = []
    for line in report.read_text().splitlines():
        if not line.strip():
            continue
        path, outcome, reasons = (line.split("\t") + ["", ""])[:3]
        rows.append((path, outcome, [r for r in reasons.split(" | ") if r]))

    counts: dict[str, int] = {}
    for _, outcome, _ in rows:
        counts[outcome] = counts.get(outcome, 0) + 1

    generator = [r for r in rows if r[0] == GENERATOR]
    if not generator or generator[0][1] != "EXECUTED":
        print(
            f"stamp-invariance: {GENERATOR} did not execute in the probe pass "
            f"({generator[0][1] if generator else 'absent from the report'}), so the version stamp "
            f"never changed and this report proves nothing. Pass a -Pversion that differs from the "
            f"first pass. See {ISSUE}.",
            file=sys.stderr,
        )
        return 1

    buckets: dict[str, list[tuple[str, list[str]]]] = {}
    for path, outcome, reasons in rows:
        if outcome == "FAILED":
            buckets.setdefault("failed", []).append((path, reasons))
            continue
        if outcome != "EXECUTED":
            continue
        if any(fnmatch.fnmatchcase(path, p) for p in allow):
            kind = "allowlisted"
        else:
            kind = classify(reasons)
        buckets.setdefault(kind, []).append((path, reasons))

    summary = ", ".join(f"{n} {k}" for k, n in sorted(counts.items()))
    print(f"stamp-invariance: {len(rows)} tasks recorded ({summary}).")
    for kind in ("allowlisted", "never up to date", "only its output moved"):
        tasks = buckets.get(kind, [])
        if tasks:
            print(f"  re-ran, {kind}: " + ", ".join(p for p, _ in tasks))

    bad = [k for k in ("leak", "unmeasured", "failed") if buckets.get(k)]
    if not bad:
        print("stamp-invariance: OK — changing the version re-ran nothing outside the allowlist.")
        return 0

    out = sys.stderr
    if buckets.get("leak"):
        print(
            "\nstamp-invariance: changing ONLY the version stamp re-ran tasks that are not allowed to "
            f"read it ({ISSUE}). Every snapshot publish changes the stamp, so each of these re-runs on "
            "every snapshot, and on native and wasm everything above it does too:",
            file=out,
        )
        for path, reasons in buckets["leak"]:
            print(f"  {path}\n      because: " + "; ".join(reasons or ["(no reason recorded)"]), file=out)
        print(
            "\nThe usual cause is a dependency on :kuilt-base (or a module above it), a `const` "
            "holding the version, or a build-logic change that makes the version a task input. If "
            "the module really does need KuiltVersion, add its tasks to "
            ".github/stamp-invariance-allowlist.txt in the same PR and say why.",
            file=out,
        )
    if buckets.get("unmeasured"):
        print(
            "\nstamp-invariance: the probe selected tasks the first pass never ran, so it cannot "
            "tell whether the stamp reached them. Narrow `stampProbeSelection` in the root build, or "
            "run them in the first pass:",
            file=out,
        )
        for path, _ in buckets["unmeasured"]:
            print(f"  {path}", file=out)
    if buckets.get("failed"):
        print("\nstamp-invariance: tasks FAILED in the probe pass:", file=out)
        for path, _ in buckets["failed"]:
            print(f"  {path}", file=out)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
