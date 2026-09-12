# Structural audit, 2026-09-06

Evidence stash for the umbrella issue https://github.com/tractat-us/kuilt/issues/2752 (#2752). Measured against `origin/main` `d5a632ba`.
This directory is a data stash, not documentation: nothing here is kept current, and the
branch carrying it is not meant to merge.

| Path | What it is |
|---|---|
| `kuilt-structural-audit.html` | The rendered synthesis (also published as a Claude artifact). |
| `reports/A-fabric-duplication.md` | Matrix of all 34 production `Seam` implementations: which lifecycle concerns are delegated vs hand-rolled, what a shared skeleton absorbs, why the radios bypassed `Connection`/`MeshSeam`. |
| `reports/B-guards.md` | All 30 root-build guard tasks: line ranges, property, baseline/ALLOW population, T/C/L/P classification, what remains after types + detekt 2.x. |
| `reports/C-test-infra.md` | Conformance suites and their fixture shapes, the fault-injection matrix, duplicated helpers, harness discipline, fixture knobs. |
| `reports/D-issue-clusters.md` | 487 issues since W31 classified by root-cause class, spawn provenance, per-class timelines, already-done open issues. |
| `reports/E-god-objects.md` | The 13 largest production files: responsibilities, churn, decomposition seams, what #1121/#1122 extracted and how the files regrew. |
| `reports/F-cancellation.md` | All 368 exception-catching sites in production source by intent and module, the wrong sites, guard coverage, the `trySendTo` collapse. |
| `data/issues.json` | Every issue (number, title, labels, dates, state) at audit time. |
| `data/bodies.json` | First ~1,800 chars of body + last comment for #1607 onward. |
| `data/recent_titles.txt`, `data/titles487.txt` | Title dumps used for the keyword passes. |
| `data/cls2.json`, `data/parents.json` | Per-issue class assignments and spawn parents from report D. |
| `data/F_rows.json`, `data/F_catch_all.txt`, `data/F_rcc_all.txt` | The classified catch-site population from report F. |
| `data/guards.txt`, `data/ratios.txt` | Guard names; comment-to-code ratios for the God-object files. |
| `method/*.py`, `method/E_ratio.awk` | The scripts that produced the numbers, kept so a later run can re-measure. |
