# `:kuilt-warp` — epic slices

> Part of the [`:kuilt-warp`](warp-vision.md) dream; the bridge from dream to
> execution. Read the [walk](warp-vision.md) first and the
> [deeper-waters index](warp-deeper.md) for the machinery. **This page decides
> nothing on its own** — it decomposes *what the work would be* if the dream were
> pursued, and is honest that only one slice is real near-term. Speculative
> (#665, spike #680).

## The slices

If `:kuilt-warp` were ever built, it decomposes into seven slices. The crucial
fact, visible the moment you draw the dependencies: **slice A is detached from the
whole graph** — it needs none of warp and is shippable today — while everything
else hangs off the throwaway spike (#680).

| Slice | What | Gated on | Reality |
|---|---|---|---|
| **A — Offline OTel exporter** | `WarpTelemetry` over kuilt's existing anti-entropy ([observability](warp-observability.md)) | *nothing* — builds today | **real, useful now** |
| **0 — The spike (#680)** | minimal `Warp`: TaskQueue/Scheduler/Results, one shuttle/weave, measure where CALM bites | sim harness | the throwaway probe |
| **B — The type seam** | `CoordinationFree`/`Coordinated` + `embroider`/commit | 0 | dream |
| **C — Code mobility** | named-ops → wasm kernels + bobbin/creel cache ([execution](warp-execution.md)) | 0 | dream |
| **D — Compiler nodes** | tiered compilation; Kotlin/Wasm + Graal bootstrap | C | dream |
| **E — Query planning** | `Draft → Draft`, coordination-cost model ([planning](warp-planning.md)) | 0 | dream |
| **F — Federated ML demo** | FedAvg on the substrate ([AI & modelling](warp-ml.md)) | 0 (+ C for model kernels) | dream |

## The dependency map

![Epic slices as a dependency graph: slice A (the offline OTel exporter) sits detached in a 'reality' band because it needs no warp and builds today; the spike #680 is the root of a 'dream' cluster that everything else hangs off — B (type seam), C (code mobility) and E (query planning) depend on the spike, D (compiler nodes) and F (federated ML) depend on C. Arrows mean 'needs'.](images/warp/slice-map.svg)

## The phased roadmap

![Epic slices as a phased roadmap: a top 'reality' lane holds slice A running in parallel, needing no warp; below it a sequential 'dream' lane runs Phase 0 (the spike) → Phase 1 (the type seam) → Phase 2 (code mobility, then compiler nodes) → Phase 3 (query planning) → Phase 4 (federated ML). Each phase is one epic sub-issue with its own green checkpoint.](images/warp/slice-phases.svg)

## Reading the slices

- **A is the only honest near-term move.** It rides shipping kuilt, solves a real
  problem (cross-endpoint log reconciliation), and proves the ideas against real
  users without committing to the grid. If anything here becomes work, it starts
  here.
- **0 is the gate for the rest.** Every dream slice depends on the spike, whose own
  job is to find out where the CALM boundary actually bites — i.e. whether the rest
  is worth doing at all.
- **Each phase is one sub-issue, one behaviour, one green checkpoint** — the epic
  convention. Phase 2 is genuinely two (C then D); a phase that hides several moves
  is several phases.
- **Nothing here is scheduled.** This roadmap exists so the decomposition is legible
  *if* the call to pursue is ever made — not as a commitment to make it.
