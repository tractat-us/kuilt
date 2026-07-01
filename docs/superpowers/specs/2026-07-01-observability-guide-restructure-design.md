# Observability guide restructure — design

> Design record for the Writerside guide restructure brainstormed 2026-07-01.
> Docs-only. Accessible-first conventions in the repo `CLAUDE.md` apply throughout.

## Goal

Make the Observability section legible to a non-technical reader and give each
telemetry signal room to be explained on its own terms. Four coordinated moves,
plus a trim of the overview page.

## 1. Overview page (`Writerside/topics/overview.md`)

- Rename the section heading `## Three building blocks — pick what you need` →
  `## Building blocks — pick what you need`.
- Add a fourth block, **Observability**, naming the trace/metric/log split in
  plain language: records three kinds of note — **traces** (how long something
  took), **metrics** (running counts and levels), and **logs** (text lines) —
  saved on the device first and delivered with no duplicates once the network
  returns. Under the hood: `kuilt-otel`.
- The first three blocks are *guarantees you compose*; Observability is
  orthogonal. In the "Pick by the guarantee you need" list, add one line saying
  Observability layers on independently — not a fourth guarantee.
- **Modules at a glance → highlights only.** Keep 7 rows: `kuilt-core`,
  `kuilt-crdt`, `kuilt-quilter`, `kuilt-raft`, `kuilt-game`, `kuilt-websocket`,
  `kuilt-otel`. Move the full 17-row table to a new Internals page and link to it.

## 2. New page — `Writerside/topics/modules.md` (Internals › "All modules")

- H1: `# All modules`. Holds the complete module table (the 17 rows currently on
  the overview), verbatim. Overview's trimmed table links here.

## 3. Observability section restructure

Tab renames use the page H1 (Writerside derives the nav label from it); no
`toc-title` overrides needed. New per-signal pages carry the "what is this"
framing in the body so the nav stays single-word.

New TOC (`Writerside/kuilt.tree`), Observability section:

```
Observability                         (section, unchanged)
├─ Device to dashboard   observability.md   (renamed from "Observability")
├─ Traces                otel-traces.md      (NEW)
├─ Metrics               otel-metrics.md     (NEW)
└─ Logs                  otel-logs.md        (NEW)
    └─ Capturing         log-capture.md      (renamed from "Capturing & pulling logs", re-parented under Logs)
```

### `observability.md` → "Device to dashboard"

Becomes the **journey** page, not the per-signal reference. H1 → `# Device to
dashboard`. Keep the offline-first framing and the two cross-cutting beats:
**survives being offline** and **see it in a dashboard** (the OTLP-bridge story,
already corrected to all-three-signals). Introduce the three kinds briefly with
links out to their pages, instead of inlining each signal's record detail.

### `otel-traces.md` / `otel-metrics.md` / `otel-logs.md` (NEW)

Each is a short, accessible-first page:

1. Plain-language "what this is" opener with a concrete everyday example (no
   jargon in the first paragraph).
2. How to record it — the relevant `WarpTelemetry` snippet, redistributed from
   the current `observability.md` Record section (spans → Traces, the
   `MetricKey`/sum/cardinality snippet → Metrics, `logs.export` → Logs).
3. A one-line pointer to **Device to dashboard** for the offline + delivery
   story (do not repeat it).

Logs page additionally links down to its nested **Capturing** child for the
"record your existing SLF4J logs / pull them off the device" how-to.

### `log-capture.md` → "Capturing"

H1 → `# Capturing`. Content unchanged apart from the title; now nested under Logs.

## Non-goals

- No code/API changes. No changes to `@sample` sources.
- Historical planning docs under `docs/superpowers/` are left as dated records.
- Section name "Observability" stays (it is the established plain section name,
  per the repo heading convention).

## Verification

- Writerside guide build (`build-guide`) green; Dokka is unaffected (no
  `module.md`/KDoc touched).
- Every new/renamed topic is referenced in `kuilt.tree` and every `kuilt.tree`
  entry resolves to a file (no orphan/dangling topics).
- Any `@sample`/verbatim citation moved between pages keeps its
  `<!-- condensed from … -->` / `<!-- verbatim from … -->` comment.
