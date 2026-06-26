# Spec — warp Phase G: `Draft` becomes a DAG; consolidate coordination rounds

**Status:** design approved (brainstorm 2026-06-25). Epic-sized follow-up to Phase E
(epic [#854](https://github.com/tractat-us/kuilt/issues/854)). Supersedes the narrower
issue [#907](https://github.com/tractat-us/kuilt/issues/907) (promoted to this epic).
Sibling track: native metrics ([#906](https://github.com/tractat-us/kuilt/issues/906),
spec `2026-06-25-warp-native-metrics-design.md`), landed first so Phase G is born
instrumented.

## Motivation

E-3 delivered a coordination-cost model, but its `rounds` field is structurally
pinned at ≤1: a `Draft` is a linear pipeline with a single `Embroider`, so the
planner can shrink coordinated *volume* (it showed ≈95%) but cannot reduce round
*count*. Real distributed queries have **multiple coordination points**. The
prize: make round count an active lever.

The theory (from the 2026-06-25 discussion): when a query has several agreements,
**independent** agreements can be **batched into one consensus round** (Raft
agrees on a tuple in the same round-trip it agrees on a scalar); only
**value-dependent** agreements force sequential rounds. So:

> **min rounds = depth of the coordination dependency DAG** — not the count of
> coordinated stages. Independent agreements at the same level collapse into one
> round; a dependency chain forces one round per level (a Brent-style
> critical-path argument).

You cannot express or measure this on a linear single-embroidery list. Hence the
model change.

## The model decision: `Draft` → DAG (path-preserving)

`Draft(stages: List<DraftStage>)` becomes a dependency DAG: each stage is a node
with a set of predecessor edges. **A linear pipeline is the degenerate case** —
the path where every node has exactly one predecessor. This is the honest
representation (a query plan *is* a DAG; the E-1 list was the MVP), and it makes
dependency structure **first-class and structural** rather than declared or
assumed — which also pays down E-2's pushdown "filter doesn't depend on the map's
output" assumption and reorder's commutativity, turning both from *assumed* into
*provable*.

### What concretely changes (the "spend"), and what doesn't

| Code | Change | Notes |
|---|---|---|
| `Draft` / `DraftStage` | add node id + predecessor set; path = degenerate DAG | one type, modest |
| `Draft.embroidery: Embroider?` | becomes plural (a set / structure) | public signature change (pre-1.0, fine) |
| E-2 `deferEmbroidery`/`pushdownFilters`/`fuseAdjacent` | list-index logic → graph-local logic (predecessors, chain-of-one) | the real work |
| E-3 `coordinationCost` | `rounds` 0/1 → DAG depth; volume walks predecessors | `WarpPlanner.kt` |
| E-1/2/3 tests | list assertions → graph assertions; behaviour identical | mechanical, largest line-count |
| **E-4 `WarpStats`** | **untouched** — never references `Draft` | — |
| **E-5 execution** | **untouched** — keep a `stages` accessor returning nodes in topological order; E-5 reads only `isMonotone` + `stages` | — |

This is a pure generalization: existing single-embroidery semantics still hold on
a path. G1 lands it with **no behaviour change** — it is a refactor verified by
the (re-asserted) E-1/2/3 suites passing.

## Slice decomposition

Mirrors E (foundational migration first, then build, then cost + go/no-go, then
execution, then polish). `:kuilt-warp` only except G5 (raft wiring).

- **G1 — `Draft` → DAG (foundational, prerequisite).** Reshape to nodes+edges,
  path-preserving builder, generalize E-2 rewrites + E-3 cost to graph ops, keep
  `stages` topological-order view (E-5 untouched), re-assert E-1/2/3 tests.
  **Acceptance:** all prior warp tests green, no behaviour change, `:kuilt-warp:build`
  + `detektAll` clean.
- **G2 — `combine`.** `Draft.combine(other)` (B-3 `zip` spirit) to express
  independent coordinated branches; the DAG can now hold multiple `Embroider`
  nodes. **Acceptance:** a combined draft has the two branches' embroideries as
  independent nodes; dependency expressed by building one branch from another's
  result.
- **G3 — `consolidateEmbroideries`.** Group `Embroider` nodes by topological level
  (no mutual dependency path); fuse each level's independent agreements into one
  `BatchedEmbroider(opIds)` (analogous to `FusedMap`). **Result-preserving**
  (`isEquivalentTo`). **Acceptance:** N independent embroideries at one level →
  one `BatchedEmbroider`; a dependency chain stays separate.
- **G4 — cost = DAG-depth + go/no-go.** `CoordinationCost.rounds` = depth of the
  coordination sub-DAG; add a **coupling/blast-radius term** (batch size /
  retry-amplification) so the planner doesn't over-batch blindly. **Go/no-go:** on
  a representative multi-coordination query, consolidation **measurably cuts round
  count** vs. unplanned (the cut E-3 couldn't show). If it can't, report NO-GO
  honestly.
- **G5 — batched Raft execution.** Drive the consolidated round through real Raft:
  one proposal per dependency level carrying that level's tuple, via the existing
  `kuilt-raft` wiring (`Coordinated.commit` path from B-2). **Go/no-go:** the round
  count measured at execution matches the DAG depth; an unplanned vs. planned run
  shows fewer real Raft rounds. Uses the canonical multi-node sim harness
  (`StandardTestDispatcher`, seeded RNG, bounded advance, tight timeout).
- **G-polish.** module.md + guide descent for planning-with-consolidation,
  `@sample` coverage, the coupling-tradeoff honesty note, cleanup.

**Critical path:** G1 → G2 → G3 → G4 → G5 → polish. (Less parallelism than E — the
DAG migration is a hard prerequisite and each slice builds on the prior.) Metrics
#906 runs first/parallel.

## Cost model detail (G4)

- `rounds` = longest chain of value-dependent `Embroider`/`BatchedEmbroider` nodes
  = depth of the coordination sub-DAG.
- `coordinatedVolume` (kept from E-3) = per-embroidery volume via predecessor walk,
  aggregated across the DAG.
- **coupling term** = a penalty rising with batch size / number of independent
  commits fused into one round, so the objective is "fewest rounds **without**
  recklessly coupling independent failure domains." The planner minimises
  lexicographically: rounds first, then a balance of volume and coupling. This
  encodes the honest tradeoff (consolidation trades round-count for blast radius /
  retry amplification / latency-for-earliest-ready).

## Module impact

- `:kuilt-warp` for G1–G4 (+G-polish). G5 adds the `:kuilt-raft` execution wiring
  already used by B-2's `Coordinated.commit`.
- No new module. `explicitApi`, `detektAll`, canonical sim harness, seeded RNG,
  `StandardTestDispatcher` throughout.

## Relationship to metrics (#906)

Phase G's G4 emits `warp.coordination.rounds` (now = DAG depth) and
`warp.coordination.volume` into the native-metrics bridge. #906 lands first or in
parallel so the round-count reduction is observable live, not retrofitted — the
"born instrumented" intent.

## Out of scope

- Arbitrary author-drawn raw DAGs as the *only* surface — the fluent builder
  (path) + `combine` (branches) remain the authoring surface; the DAG is the
  representation, not a hand-wiring chore.
- Cross-query / cross-request round batching (amortising many drafts' embroideries
  into shared Raft entries) — a further optimisation; G batches *within* a draft.
- Secure/Byzantine agreement — orthogonal.

## Go/no-go summary

Two gates: **G4** (analytical/structural round-count cut on the sim) and **G5**
(round-count cut at real-Raft execution). Either failing is a legitimate stop —
report with evidence rather than manufacturing a result, per the E-3 discipline.
