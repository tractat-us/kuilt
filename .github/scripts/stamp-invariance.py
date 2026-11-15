#!/usr/bin/env python3
"""Fail when a changed version stamp re-ran a task that does not read it (#3464).

Usage: stamp-invariance.py <report.tsv> <selected-dir> [allowlist]

The report is written by the root build's `StampProbeRecorder` during the SECOND pass of
`.github/scripts/stamp-invariance.sh`: one `<task path>\t<outcome>\t<reasons>` row per task, where
the reasons are Gradle's own "why did this execute" lines. The first pass is the job's normal build
at the job's normal version; the second runs `stampProbeTargets` at a different `-Pversion`. Every
selected task should then be UP-TO-DATE, because the stamp is the only input that changed.

Only the SELECTED tasks are judged: the compile and test tasks each project's `stampProbeTargets`
listed in <selected-dir>. The helpers they pull in (jars, KGP's metadata transforms, lifecycle
tasks) re-run whenever a file NAME carries the version; if a helper's content changed, a selected
task downstream re-runs and is caught there. Each selected task that EXECUTED is classified from
Gradle's own reasons, never from its name:

- allowlisted        — matches a line of the allowlist (the carrier, `:kuilt-base`).
- only a path moved  — every reason is KGP's `friendPathsSet` value: a JVM test compile is keyed on
                       its main jar's PATH, and the jar's file name carries the version. The content
                       of its inputs is unchanged, so its output is too. Publishing compiles no tests.
- unmeasured         — "No history is available": the first pass never ran it, so this run cannot
                       say whether the stamp reached it. That is a selection bug, and it fails too,
                       so the probe cannot quietly grow into a second build.
- leak               — anything else.

Two preconditions are asserted first, because a clean report proves nothing without them:
`:kuilt-base:generateKuiltVersion` EXECUTED (so the stamp really changed), and every selected task
appears in the report (so the probe ran what it selected).
"""
from __future__ import annotations

import fnmatch
import sys
from pathlib import Path

ISSUE = "https://github.com/tractat-us/kuilt/issues/3464"
GENERATOR = ":kuilt-base:generateKuiltVersion"


def load_lines(path: Path) -> list[str]:
    out = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def classify(reasons: list[str]) -> str:
    if any("No history is available" in r for r in reasons):
        return "unmeasured"
    if reasons and all("input property 'friendPathsSet" in r for r in reasons):
        return "only a path moved"
    return "leak"


def main(argv: list[str]) -> int:
    if len(argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        return 2
    report = Path(argv[1])
    selected: set[str] = set()
    for f in sorted(Path(argv[2]).glob("*.txt")):
        selected.update(load_lines(f))
    allowlist_path = (
        Path(argv[3]) if len(argv) == 4
        else Path(__file__).resolve().parents[1] / "stamp-invariance-allowlist.txt"
    )
    allow = load_lines(allowlist_path)

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
        state = generator[0][1] if generator else "absent from the report"
        print(
            f"stamp-invariance: {GENERATOR} did not execute in the probe pass ({state}), so the "
            f"version stamp never changed and this report proves nothing. Pass a -Pversion that "
            f"differs from the first pass. See {ISSUE}.",
            file=sys.stderr,
        )
        return 1

    recorded = {r[0] for r in rows}
    missing = sorted(selected - recorded)
    if not selected or missing:
        print(
            f"stamp-invariance: {len(selected)} tasks selected, {len(missing)} of them absent from "
            f"the report ({', '.join(missing[:10])}). The probe did not run what it selected, so a "
            f"clean verdict would mean nothing. See {ISSUE}.",
            file=sys.stderr,
        )
        return 1

    buckets: dict[str, list[tuple[str, list[str]]]] = {}
    helpers = 0
    for path, outcome, reasons in rows:
        if outcome == "FAILED":
            buckets.setdefault("failed", []).append((path, reasons))
            continue
        if outcome != "EXECUTED":
            continue
        if path not in selected:
            helpers += 1
            continue
        if any(fnmatch.fnmatchcase(path, p) for p in allow):
            kind = "allowlisted"
        else:
            kind = classify(reasons)
        buckets.setdefault(kind, []).append((path, reasons))

    summary = ", ".join(f"{n} {k}" for k, n in sorted(counts.items()))
    print(
        f"stamp-invariance: {len(rows)} tasks recorded ({summary}); {len(selected)} selected and "
        f"judged; {helpers} unselected helpers re-ran and are not judged."
    )
    for kind in ("allowlisted", "only a path moved"):
        tasks = buckets.get(kind, [])
        if tasks:
            print(f"  re-ran, {kind} ({len(tasks)}): " + ", ".join(p for p, _ in tasks))

    if not any(buckets.get(k) for k in ("leak", "unmeasured", "failed")):
        print("stamp-invariance: OK — changing the version re-ran no selected task outside the allowlist.")
        return 0

    out = sys.stderr
    if buckets.get("leak"):
        print(
            f"\nstamp-invariance: changing ONLY the version stamp re-ran {len(buckets['leak'])} "
            f"tasks that are not allowed to read it ({ISSUE}). Every snapshot publish changes the "
            "stamp, so each of these re-runs on every snapshot, and on native and wasm everything "
            "above it does too:",
            file=out,
        )
        for path, reasons in buckets["leak"]:
            print(f"  {path}\n      because: " + "; ".join(reasons or ["(no reason recorded)"]), file=out)
        print(
            "\nThe usual cause is a dependency on :kuilt-base (or on a module above it), a `const` "
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
