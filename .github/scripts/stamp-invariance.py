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
- unmeasured         — "No history is available": the first pass never ran it, so this run cannot
                       say whether the stamp reached it. That is a selection bug, and it fails too,
                       so the probe cannot quietly grow into a second build.
- leak               — anything else. That includes a JVM test compile re-run only by KGP's
                       `friendPathsSet`: by default its friend set names the main jar by path, and
                       the jar's file name carries the version. `gradle.properties` turns that off
                       (`kotlin.build.archivesTaskOutputAsFriendModule=false`), so if that reason
                       comes back the flag was removed or the Kotlin plugin changed how it keys
                       friends, and every snapshot is recompiling every JVM test compile again.

Three preconditions are asserted first, because a clean report proves nothing without them:
`:kuilt-base:generateKuiltVersion` EXECUTED (so the version stamp really changed); a jar this pass
wrote carries `SCM-Revision: stamp-probe` and none carries anything else (so the commit stamp
really changed too, #3583); and every selected task appears in the report (so the probe ran what
it selected). The jar is read rather than the report, because Gradle stops listing a jar task's
reasons at its renamed output and never gets as far as the manifest.
"""
from __future__ import annotations

import fnmatch
import os
import sys
import zipfile
from pathlib import Path

ISSUE = "https://github.com/tractat-us/kuilt/issues/3464"
GENERATOR = ":kuilt-base:generateKuiltVersion"
# What stamp-invariance.sh passes as -Pkuilt.sourceRevision, and the suffix it gives -Pversion.
PROBE_REVISION = "stamp-probe"
PROBE_JARS = ("*/build/libs/*.stamp-probe*.jar", "*/*/build/libs/*.stamp-probe*.jar")


def probe_revisions(since: float) -> dict[str, str]:
    """`SCM-Revision` of every probe jar written at or after `since`, by path. A jar without the
    attribute (a metadata jar, say) is left out: `kuilt.publish` stamps class and sources jars only."""
    found: dict[str, str] = {}
    for pattern in PROBE_JARS:
        for jar in Path(".").glob(pattern):
            if jar.stat().st_mtime < since:
                continue
            with zipfile.ZipFile(jar) as z:
                try:
                    manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8")
                except KeyError:
                    continue
            for line in manifest.splitlines():
                if line.startswith("SCM-Revision:"):
                    found[str(jar)] = line.split(":", 1)[1].strip()
    return found


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

    # Jars written since the probe pass began; run by hand without the script, every probe jar.
    # Only the `linux` family reads jars. The `apple` family compiles against klibs, which carry no
    # commit stamp, so it writes no class jar and there is nothing for this precondition to read.
    revisions = probe_revisions(float(os.environ.get("STAMP_PROBE_START", "0")))
    stamped = sorted(j for j, r in revisions.items() if r == PROBE_REVISION)
    other = sorted(f"{j} ({r})" for j, r in revisions.items() if r != PROBE_REVISION)
    if os.environ.get("STAMP_PROBE_FAMILY", "linux") == "linux" and (not stamped or other):
        print(
            f"stamp-invariance: {len(stamped)} jars written by the probe pass carry "
            f"`SCM-Revision: {PROBE_REVISION}`, and {len(other)} carry another "
            f"({', '.join(other[:5])}). The commit stamp never changed on the test classpath, so "
            f"this report cannot see it leak. Pass -Pkuilt.sourceRevision={PROBE_REVISION} to the "
            f"probe pass. See https://github.com/tractat-us/kuilt/issues/3583.",
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
        # FROM-CACHE counts as a re-run: a task is only looked up in the cache when it is NOT up to
        # date, i.e. when an input changed. The script passes --no-build-cache, so this is a backstop.
        if outcome not in ("EXECUTED", "FROM-CACHE"):
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
    for kind in ("allowlisted",):
        tasks = buckets.get(kind, [])
        if tasks:
            print(f"  re-ran, {kind} ({len(tasks)}): " + ", ".join(p for p, _ in tasks))

    if not any(buckets.get(k) for k in ("leak", "unmeasured", "failed")):
        print(
            "stamp-invariance: OK — changing the version and the commit re-ran no selected task "
            f"outside the allowlist ({len(stamped)} probe jars carry SCM-Revision: {PROBE_REVISION})."
        )
        return 0

    out = sys.stderr
    if buckets.get("leak"):
        print(
            f"\nstamp-invariance: changing ONLY the version and commit stamps re-ran "
            f"{len(buckets['leak'])} tasks that are not allowed to read them ({ISSUE}). Every "
            "snapshot publish changes both, so each of these re-runs on every snapshot, and on "
            "native and wasm everything above it does too:",
            file=out,
        )
        for path, reasons in buckets["leak"]:
            print(f"  {path}\n      because: " + "; ".join(reasons or ["(no reason recorded)"]), file=out)
        print(
            "\nThe usual cause is a dependency on :kuilt-base (or on a module above it), a `const` "
            "holding the version, or a build-logic change that makes the version a task input. A "
            "test task re-run only by `stableClasspath` most likely means a jar manifest attribute "
            "that runtime-classpath normalization does not ignore (see the root build, #3576). If "
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
