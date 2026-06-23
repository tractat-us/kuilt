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

## Provisional issues — the full mapping

One node per shippable sub-issue, **split finer where the work is genuinely
different** (the per-target wasm runtimes; the per-platform durable store; the two
compiler toolchains). Grouped by the spine payoff each one backs — that grouping
*is* the spine's [third axis](warp-deeper.md).

**Compute engine** — slices 0 / B / C / D
- `0a` TaskQueue (ORSet) · `0b` TaskScheduler (equalizer @ depth) · `0c` Results
  (ORMap) → `0d` shuttle/weave surface → `0e` sim-harness CALM measurement **(#680)**
- `B1` Coordination(Free/-ated) types → `B2` embroider/commit(raft) · `B3` vetted
  monotone combinators
- `C1` op registry + KSP → `C2` task-descriptor envelope → wasm runtimes
  (**split per target**): `C3·browser` (native) · `C3·jvm` (Chicory) · `C3·macos`
  (wasmtime JIT) · `C3·ios` (wasm3 interpret) ; `C4` bobbin + creel → `C5` lazy
  bobbin gossip
- `D1` compile op → `D2` bobbin variants → `D3` tiered compilation ; toolchains
  (**split**): `D4·kwasm` (Kotlin/Wasm authoring) · `D4·graal` (GraalWasm node)

**Planning**
- `E1` Draft reified → `E2` rewrite rules → `E3` coordination-cost model ; `E4`
  HyperLogLog stats gossip · `E5` incremental / threshold-read execution

**Observability** *(part of A)*
- `A2` SpanExporter (ORSet) · `A4` LogRecordExporter (Rga) · `A8` causal-trace
  inference

**Telemetry — offline** *(part of A, the real one)*
- `A1` DurableStore abstraction (**+3 platform WALs**: SQLite / IndexedDB / native)
  → `A3` MetricExporter (cumulative) · `A5` WarpOtlpBridge ; `A6` bounded-buffer
  eviction · `A7` KMP + OTel SDK glue

**Federated ML**
- `F1` FedAvg counter-weave · `F2` model-as-wasm-kernel → `F4` end-to-end demo ;
  `F3` secure aggregation *(optional)*

**Cross-group gates** (what binds the DAG): `0e` gates every dream cluster;
`0e + B1 → E1`; `C2 → F2`; `0e → F1`; `C` (via `C2`) → `D1`. The **A cluster is its
own connected component** — gated on nothing.

## The full issue DAG

![The full provisional-issue dependency graph, grouped into clusters by the spine payoff each issue backs: a detached green Reality cluster (the offline exporter — A1 store → A2/A3/A4 exporters → A5 bridge, plus A6/A7/A8) gated on nothing; and the dream clusters Compute (0 → B → C → D, with the wasm runtime split per target and the toolchains split in two), Planning (E), and Federated ML (F). Bold inter-cluster arrows show the gates: the #680 spike gates every dream cluster, and C2 gates the model kernel F2.](images/warp/slice-dag.svg)

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
