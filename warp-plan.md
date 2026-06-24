# warp — push-ahead plan (dream → scheduled)

_2026-06-24. Anchored to `docs/warp-slices.md` (A · 0 · B · C · D · E · F). The docs framed
everything except slice A as "dream, not to be scheduled." This plan promotes it — deliberately —
because the spikes turned the open question into a measured one._

## Where we are (what the spikes established)

The compute-substrate question — *can CRDT-gossip schedule work without per-task consensus?* — is
no longer hand-waved. The spike (#680) + Strategy D (#796) mapped the design space:

| Strategy | Dup rate | Coordination | Verdict |
|---|---|---|---|
| Optimistic-dedup | 17–67% (grows with peers) | 0/task | cheap, wasteful at scale |
| Intent-register | →0 at low N, degrades | ~consensus | no free lunch (per-task) |
| Consensus | 0 | 4–18 msgs/task | correct, cost-competitive |
| **Consistent hashing** | **~0 for stable membership** | **~0/task** | **the unlock** |

**The reframe:** warp is viable as a coordination-free compute substrate **for stable-membership
workloads** — a seated game lobby, a server fleet, a focused work window — via **consistent-hashing
assignment + an `ORMap` dedup backstop + liveness-driven failover**. The cost relocates from
per-task to per-membership-change (CALM), which is the whole win when tasks ≫ membership churn.
Slice A (offline OTel exporter) already shipped — the detached "reality" component.

## Phase 0 — answer (a): measure the exact viable regime  ⏳ RUNNING

v4 of the spike (#796) adds **membership-propagation lag** (the v3 gap: membership converged
instantly, so the failure mode never appeared). It sweeps **churn × convergence-lag** and reports
the **crossover contour** — the threshold below which consistent hashing stays ~0-dup. 

**Go/no-go gate:** if the viable regime (low churn × fast gossip) covers a real target workload
(it does for sessions/server fleets; the open question is high-churn mobile/browser), **promote to
Phase 1.** If even stable membership is fragile, stop and keep warp a documented dream.

## Phase 1 — graduate slice 0 to a real foundation  (the first scheduled warp work)

Promote the throwaway `:examples` sim into a real **experimental** module `:kuilt-warp` — NOT in the
default target set / BOM until Phase 1 proves itself on a real `Seam`. This is slice **0 → real**
plus the slice **B** type seam.

- **`TaskRing`** — consistent-hash assignment over a roster (virtual nodes; pluggable ring source).
  *Name the role, reveal the primitive:* the ring **is** the `:kuilt-session` `Room` roster (eventual)
  or `:kuilt-raft` dynamic membership (strong) — the v2/v4 trade made a configuration knob.
- **`WorkQueue`** (ORSet) · **`Results`** (ORMap, the dedup backstop) · **`WarpNode`** wiring them
  over a real `Seam`, with **liveness-driven failover** (`:kuilt-liveness` `PartitionDetector` →
  next-on-ring takes over a partitioned owner).
- **Slice B — the type seam:** `CoordinationFree` (monotone → hashed, no coordination) vs
  `Coordinated` (exclusive → escalate to dedup/consensus). `embroider`/`commit`. B3 vetted monotone
  combinators.
- **First real deliverable:** run a task fleet over `InMemoryLoom` *and* a real fabric
  (`:kuilt-websocket`), measure the real dup-rate against the spike's prediction, prove convergence
  (all `Results` agree, no task lost) under induced churn/partition.
- **Discipline:** multi-node tests through the canonical sim harness (never hand-rolled); seeded RNG;
  `StandardTestDispatcher`; thread-safe via explicit primitives; `runCatchingCancellable`. TDD.

**Phase 1 issues to file on go:** epic `:kuilt-warp` foundation + sub-issues `TaskRing` ·
`WorkQueue/Results` over Seam · `WarpNode` + failover · ring-source (gossip vs raft) · `B1`
Coordination types · `B2` embroider/commit · `B3` combinators. One behavior per PR.

## Phase 2 — the dream slices, in dependency order (gated on Phase 1 proving the foundation)

- **E — Query planning** first (cheap, high-leverage): `Draft → Draft` rewrite + a **coordination-cost
  model directly seeded by the spike's measured costs** (optimistic vs intent vs consensus vs
  consistent-hash). `E4` HyperLogLog stats gossip (we already have HLL). `E5` threshold-read execution.
- **C — Code mobility** (the big, genuinely-hard one): named-ops → wasm kernels + bobbin/creel cache,
  **split per target** (browser native · jvm Chicory · macOS wasmtime · iOS wasm3). Multi-month.
- **D — Compiler nodes** (needs C): tiered compilation; Kotlin/Wasm + GraalWasm toolchains.
- **F — Federated ML demo** (needs spike + C): FedAvg counter-weave, model-as-wasm-kernel,
  end-to-end demo — the dessert.

## Cross-cutting

- **Fold the consistent-hashing finding back into the docs.** `docs/warp-vision.md` /
  `warp-execution.md` currently map *TaskScheduler → BoundedCounter equalizer*; the spike showed
  **consistent-hashing-over-roster is the better scheduler primitive** for stable membership (the
  equalizer is a load-smoother on top, not the assigner). Update the mapping + the slice-0 results
  citation. (Docs-only PR.)
- **Preserve "the walk."** Per the house rule, `:kuilt-warp`'s own README/guide must descend the
  mountain (plain idea → recognition → reduction → honest seam → fantasy last). Bake it into Phase 1's
  `module.md` from the first commit, not retrofitted.
- **Stay honest about scope.** warp remains experimental (no default-target/BOM) until Phase 1's
  real-`Seam` foundation is green and measured. Each phase has its own go/no-go. C/D/F are real
  multi-month efforts — don't let the dream's appeal compress their estimates.

## Immediate next actions

1. **Phase 0 running** — await v4 crossover (#796). Read the contour; make the promote/stop call.
2. On promote: file the `:kuilt-warp` foundation epic + Phase-1 sub-issues; dispatch Phase 1
   (TaskRing/WorkQueue/Results/WarpNode are largely independent → parallel) per the EPIC convention.
3. Land the docs-only "consistent-hashing is the scheduler" update regardless (cheap, correct now).
