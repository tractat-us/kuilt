# Spec: "Extras" and "Experimental" documentation areas

**Date:** 2026-06-25
**Status:** approved-pending-review

## Goal

Two real modules landed but are absent from the published documentation surfaces:

- **`kuilt-otel`** — offline-first OpenTelemetry exporter (slices A1–A5 + platform
  WALs). Real, useful, builds today. Has a strong Dokka `module.md`.
- **`kuilt-warp`** — coordination-free distributed scheduler (TaskRing / WorkQueue /
  WarpNode / liveness failover), now graduated into the BOM (#842). Real-but-pre-1.0,
  *plus* a large family of speculative "dream" design docs (`warp-vision`,
  `warp-deeper`, `warp-execution`, `warp-planning`, `warp-ml`, `warp-slices`,
  `warp-spike-results`, `warp-foundation`, `warp-observability`).

Surface both through the published Writerside guide:

- **Observability** lives in a new **"Extras"** guide section (the `kuilt-otel` story).
- **Warp**, including the fantasy/dream design docs, lives in a new **"Experimental"**
  guide section.

Keep the README and module surfaces consistent.

## Constraints / conventions (from repo CLAUDE.md)

- **Accessible-first is mandatory** on every guide surface: lead in plain language,
  introduce jargon only after drilling in, with a one-phrase definition at first use.
  Section/topic names are the plain ones — hence **"Observability"**, not
  "Offline OpenTelemetry"; the terms *OpenTelemetry / OTel* appear only deeper in the
  body.
- **The descent** is the narrative shape for vision docs: one plain "what if" →
  recognition (the pieces already ship) → the honest seam → fantasy last.
- Guide topics live in `Writerside/topics/`; they link to design docs via absolute
  GitHub URLs (`https://github.com/tractat-us/kuilt/blob/main/docs/<file>.md`). There
  is **no** `Writerside/images` dir — design-doc images stay under `docs/images/` and
  render on GitHub.
- A single-topic section is an established pattern (`Internals` → just `performance.md`).
- No references to other `tractat-us/*` repos; describe kuilt's own behaviour.

## Design

### A. Guide TOC — `Writerside/kuilt.tree`

Add two new top-level sections **after `Internals`** (Experimental last = most
speculative, honouring fantasy-last):

```xml
<toc-element toc-title="Extras">
    <toc-element topic="observability.md"/>
</toc-element>
<toc-element toc-title="Experimental">
    <toc-element topic="warp.md"/>
</toc-element>
```

Each section has one accessible landing topic that links out to the existing design
docs and Dokka API. We do **not** re-host every warp `.md` as a Writerside topic
(would duplicate content) — the dream docs stay in `docs/` and are surfaced through
the Experimental landing page's annotated index.

### B. New guide page — `Writerside/topics/observability.md` (Extras, title "Observability")

Accessible-first. Opening (plain, no jargon): your app runs across phones, browsers,
and laptops; each device quietly records what happened — errors, timings, counts — so
you can tell whether things are working. Normally those records are lost when a device
is offline. kuilt keeps them safe on the device and syncs them up when the network
returns, with nothing duplicated and nothing lost.

Then drill in (jargon introduced here, defined at first use):
- This plugs into **OpenTelemetry** (the industry-standard way apps emit traces,
  metrics, and logs) as a standard exporter — so existing dashboards (Jaeger,
  Prometheus) work unchanged.
- The inversion: `export()` succeeds the moment data is *durably written locally*, not
  when it's delivered. Delivery is the fabric's job, whenever connectivity allows.
- Why it's correct, not merely buffered: each signal is stored as a CRDT (spans → set
  keyed by id, metrics → mergeable counters, logs → ordered sequence), so a resend is
  idempotent — the delta-temporality double-count-under-retry bug is structurally
  impossible.

Links → `kuilt-otel` Dokka module (`/api/`) and the new `docs/offline-otel.md` design
doc. Honest-limits one-liner with a pointer for depth.

### C. New guide page — `Writerside/topics/warp.md` (Experimental, title "Warp")

Follows the descent:
1. Plain "what if a roomful of devices could share one pile of work — nobody doing the
   same job twice, and no central boss telling anyone what to do."
2. Recognition — the pieces already ship (task list = `ORSet`, results board = `ORMap`,
   who-takes-what = consistent hashing over the roster, failover = liveness).
3. The honest seam — coordination-free only while the peer roster is stable; churn
   reassigns the affected arc, dedup backstop caps damage to duplicate execution, never
   wrong results.
4. **Annotated index of the dream docs** — one line each linking out to
   `warp-vision`, `warp-deeper`, `warp-execution`, `warp-planning`, `warp-ml`,
   `warp-slices`, `warp-spike-results`, `warp-foundation`, and the speculative
   `warp-observability`.
5. Fantasy last — code mobility (tasks that are *code*, not just data).

A clear banner up top: the `kuilt-warp` scheduler is real but pre-1.0 and outside the
stability surface; everything past it is speculative (research epic #665, spike #680),
no commitment to build.

### D. Split `docs/warp-observability.md`

Realises the "Split it" decision — shipped content to Extras, speculative tail to
Experimental.

- **New `docs/offline-otel.md`** — the *shipped* design rationale for `kuilt-otel`:
  the offline-first exporter, why CRDT representations make resends correct (not merely
  buffered), the OTel-adapter seams that are now real, and the honest limits. This is
  the Extras design doc that `observability.md` links to. Its image reference
  `images/warp/offline-exporter.svg` resolves unchanged (same `docs/` relative root).
- **Trim `docs/warp-observability.md`** to the *speculative* tail only — "observability
  falls out of the zoo" (traces *inferred* from the `Causal` carrier, not instrumented)
  and "possible upstream contributions" (the OTEP / HLC ideas). Add a header pointer:
  the shipped exporter is `kuilt-otel` → see `offline-otel.md`; this page is the dream
  beyond it. Stays in the warp family, surfaced under Experimental.

### E. README + module surfaces — `README.md`, `Writerside/topics/overview.md`

- **README "Modules"** (`README.md`): add a new **"Extras"** subsection with
  `kuilt-otel` (real), and an **"Experimental"** subsection with `kuilt-warp` carrying a
  short pre-1.0 / not-for-production note.
- **README "Documentation"** (`README.md`): add links to the new **Extras
  (Observability)** and **Experimental (Warp)** guide areas.
- **`overview.md` "Modules at a glance"**: add `kuilt-otel` (real shipping module);
  keep `kuilt-warp` out of the top table and instead add a one-line "Beyond the core →"
  pointer to the Extras/Experimental sections, so the accessible overview stays clean.

## Out of scope

- No changes to `kuilt-otel` / `kuilt-warp` `module.md` (already strong Dokka docs).
- No image relocations.
- No code changes.

## Acceptance criteria

- `kuilt.tree` has `Extras` (→ `observability.md`) and `Experimental` (→ `warp.md`)
  sections; both topic files exist and the guide structure is valid.
- `observability.md` and `warp.md` both read accessible → technical top-to-bottom;
  neither opens with jargon. "OpenTelemetry"/"OTel" first appears only after the plain
  framing in `observability.md`.
- `docs/offline-otel.md` exists (shipped rationale); `docs/warp-observability.md` is
  trimmed to the speculative tail with a pointer to `offline-otel.md`. No broken image
  or cross-doc links.
- README lists `kuilt-otel` (Extras) and `kuilt-warp` (Experimental, pre-1.0 note) and
  links both new guide areas; `overview.md` lists `kuilt-otel` and points to the new
  sections without dumping warp jargon up top.
- Docs-only change: `ci-required` goes green without the heavy build.
