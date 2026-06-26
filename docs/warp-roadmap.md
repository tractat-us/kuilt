# `:kuilt-warp` — Phase-2 roadmap

> The execution plan for warp after the foundation. Read the
> [walk](warp-vision.md) for *why* this exists and [warp slices](warp-slices.md)
> for the conceptual decomposition; **this page operationalizes that decomposition
> into ordered, dispatch-ready epics.** Where the slices page decides nothing, this
> one commits to a sequence — while staying honest that only the first epic is
> near-term.

## Where we are

Warp already does something real. Hand it a pile of work and a few connected
peers, and each peer quietly picks up the jobs that belong to it, runs them, and
drops the answers into a shared board everyone can read. Nobody is in charge; no
job runs twice that matters. That much is **built and measured** — the foundation
shipped in Phase 1 ([epic #809](https://github.com/tractat-us/kuilt/issues/809)).

What's left is the dream: letting a task escalate to a real agreement when it
*must* run exactly once, shipping the *code* of a job across a phone and a server
and a browser at once, planning a distributed query to spend the fewest
agreements, and running a shared machine-learning round across everyone's data
without collecting it. Those are the five epics below.

The honest part first: **only the first of them is near-term.** The other four are
laid out here so the work is legible and ready to pick up — not so it's scheduled.

## What Phase 1 already settled

Three things shipped in Phase 1 that quietly unlock most of what follows, so the
roadmap's ordering is a *choice* rather than a forced march:

- **The spike** ([#680](https://github.com/tractat-us/kuilt/issues/680)) proved
  the core design (consistent-hashing over a consensus-backed ring → ~0 duplicate
  executions at a fraction of per-task consensus cost).
- **The type seam's *types*** ([#814](https://github.com/tractat-us/kuilt/issues/814))
  — `CoordinationFree` / `Coordinated` and the first monotone combinators — landed
  as a compile-time gate.
- **The raft-backed ring** drives `WarpNode`'s task assignment today.

The published slice graph gated everything on the spike and gated query planning
on "the spike *and* the type-seam types." **Both gates are already green.** So
three of the five epics — **B, C, and E** — are each independently startable now;
only **D** and the full **F** demo still wait on code mobility. Sequencing is now
driven by *value and risk*, not by hard blockers.

## The spine

```
  B  ──►  ( E  ∥  C )  ──►  D
                    └──────►  F
```

**B first**, then **E and C in parallel**, then **D after C**, with **F** trailing
(its first piece can start anytime; its full demo waits on C). Build-next is **B**.

The reasoning: B is the smallest and most coherent — it finishes a promise the
shipped foundation already makes but doesn't yet keep. E and C are independent of
each other and both unblocked, so they parallelize cleanly. D and F's kernel work
genuinely need C's task-descriptor envelope, so they come after.

| Epic | What | Gate | Reality |
|---|---|---|---|
| **B — coordination seam** | finish the exactly-once / coordinated path | none | **committed (build-next)** |
| **E — query planning** | `Draft → Draft`, coordination-cost model | B1 types (done) | exploratory |
| **C — code mobility** | named ops → wasm kernels + bobbin/creel cache | spike (done) | exploratory |
| **D — compiler nodes** | distributed tiered compilation | C (via C2) | exploratory |
| **F — federated ML** | FedAvg on the substrate, end-to-end demo | F1 none; F2/F4 need a C3 wasm runtime | exploratory |
| **G — Draft → DAG** | reshape `Draft` into a dependency DAG; consolidate coordination rounds (min rounds = DAG depth) | E (done) | exploratory |

Every epic stays **experimental**: `:kuilt-warp` remains out of `:kuilt-bom` and
out of `kuilt.publish` for the duration. Each of C–F carries an explicit
**go/no-go** the team answers when the epic is reached — the call to build it is
deferred, not made here.

## Universal per-epic discipline

Two rules apply to *every* epic below, on top of its own sub-issues:

- **Each epic ends with a polish-pass sub-issue** — a deliberate second pass over
  the delivered code: `detektAll` clean, no leftover stubs or placeholders, the
  exception-discipline audit (`runCatchingCancellable`, never swallow cancellation),
  the module's `module.md` "walk" and KDoc/`@sample` updated to match what shipped,
  and a test-coverage / conformance review. The feature isn't done until its polish
  pass is.
- **One behaviour per PR**, multi-node tests through the canonical sim harness,
  seeded RNG, `StandardTestDispatcher`, bounded advance (never `advanceUntilIdle()`),
  thread-safety via explicit primitives (no `limitedParallelism(1)`), `explicitApi`.
  The Phase-1 guardrails carry forward unchanged.

---

## Epic B — Finish the coordination type seam

**Goal.** Make the coordinated path *real*. The type seam shipped as types, but
`WarpNode` only ever runs the coordination-free fast path, and
`Coordinated.commit` is a pure-function placeholder. This epic delivers the
exactly-once escalation the seam already advertises: a task that *must not* run
twice routes through a Raft proposal instead of the optimistic ring.

**Sub-issues.**
- **B-1 — coordination-tagged task model.** A task is either coordination-free or
  coordinated; `WarpNode` routes each kind down the correct path. (Today the
  executor is a bare `suspend (TaskId) -> String` with no tag.)
- **B-2 — `commit` → real Raft proposal.** Wire a `RaftNode` into `WarpNode` so a
  coordinated task escalates to a log proposal (exactly-once), retiring the
  pure-function placeholder. The raft ring is already available via
  `RaftNode.rosterSnapshot`.
- **B-3 — vetted monotone combinator library.** Extend B3 beyond `monotoneMap` /
  `liftCoordinationFree` / `joinAll`: the safe, tested set of combinators whose
  monotonicity is enforced by the type.
- **B-4 — exactly-once measurement.** Extend the sim harness to show dup-rate = 0
  on the coordinated path under membership churn (the coordinated counterpart to
  the foundation's dup-rate measurement).
- **B-5 — polish pass.** (cleanup / doc / testing — see universal discipline.)

**Gate.** None — foundation and raft are both done. **Committed.**

**Acceptance.** A coordinated task is guaranteed exactly-once under churn (proven
in the sim); the coordination-free path is unchanged; `Coordinated.commit` no
longer contains placeholder language; the `module.md` walk describes both paths.

---

## Epic E — Query planning

**Goal.** Make the *plan* of a distributed query a value you can inspect and
rewrite before it runs — and make the optimizer minimise **agreements** rather than
bytes. This is the clearest demonstration of warp's thesis: because every
coordination-free stage commutes, the planner may reorder and fuse freely, and push
the one expensive agreement as late and small as possible. Full design:
[warp planning](warp-planning.md).

**Sub-issues.**
- **E1 — Draft reified.** Capture `warp.shuttle(...)` as an inspectable dataflow
  graph instead of running it immediately.
- **E2 — rewrite rules.** Pushdown, reorder-and-fuse, defer-the-embroidery — each a
  `Draft → Draft` move that never changes the result.
- **E3 — coordination-cost model.** Choose rewrites by counting coordination rounds,
  not IO. (Strongest once B-2 delivers a real coordinated commit to defer.)
- **E4 — stats gossip.** Cardinalities as mergeable HyperLogLog sketches gossiped on
  the existing anti-entropy — **reuse the HLL CRDT** already in `:kuilt-crdt`
  ([#693](https://github.com/tractat-us/kuilt/issues/693)).
- **E5 — incremental / threshold-read execution.** Results that converge rather than
  finish, with LVar-style threshold reads.
- **E-polish — polish pass.**

**Gate.** B1 types (done) → startable now; E3 is most meaningful after B-2.

**Go/no-go.** Does the coordination-cost model measurably cut coordination rounds
on a representative query in the sim, versus the unplanned execution?

---

## Epic C — Code mobility

**Goal.** Today a job's *name* travels and every peer runs its own registered copy.
This epic lets the job's *code* travel — a sandboxed wasm kernel that is the same
bytes on a browser, a server, and a phone — so the grid can run computations that
weren't in the deployed binary. The largest and riskiest epic; full design:
[warp execution](warp-execution.md).

**Sub-issues.**
- **C1 — op registry + KSP.** Auto-register named ops so `shuttle { … }` reads like
  an ordinary lambda.
- **C2 — task-descriptor envelope.** The `{ op, args, traceparent }` envelope —
  routes work, doubles as the bobbin content-hash, carries a trace. (Gates D and F2.)
- **C3 — per-target wasm runtimes** (split, because each is genuinely different
  work): `C3·browser` (native), `C3·jvm` (Chicory), `C3·macos` (wasmtime JIT),
  `C3·ios` (wasm3 interpret).
- **C4 — bobbin + creel.** Content-addressed kernel cache.
- **C5 — lazy bobbin gossip.** Merkle-CRDT manifest: keys (hashes) gossip eagerly,
  bytes fetch on demand.
- **C-polish — polish pass.**

**Gate.** Spike (done) → startable now.

**Go/no-go.** Named-ops symbolic dispatch plus *one* wasm runtime, end-to-end on the
sim, before committing to all four targets.

---

## Epic D — Compiler nodes

**Goal.** Treat compilation as just another warp op (`compile(wasmHash, target)`),
so a strong peer can build an optimized kernel and gossip it to weaker peers that
started by interpreting — a JIT smeared across the mesh. Full design:
[warp execution](warp-execution.md#compiler-nodes--distributed-tiered-compilation).

**Sub-issues.**
- **D1 — `compile` op.** · **D2 — bobbin variants** (raw `.wasm` + per-`(target,
  opt-level)` compiled forms). · **D3 — tiered compilation** (interpret now, tier up
  when the compiled bobbin gossips in). · **toolchains (split):** `D4·kwasm`
  (Kotlin/Wasm authoring) · `D4·graal` (GraalWasm node).
- **D-polish — polish pass.**

**Gate.** C, via C2. **Honest asterisk:** a compiler node cannot give iOS native
execution — Apple forbids running externally-delivered machine code at all; the iOS
ceiling stays *interpret optimized wasm*.

**Go/no-go.** A peer demonstrably tiers up from interpreted to compiled on a
non-iOS target via a gossiped bobbin variant.

---

## Epic F — Federated ML demo

**Goal.** Run a shared learning round across everyone's device without collecting
their data: each peer trains locally and contributes only a model update, which
merges as a CRDT. The payoff demo for the whole substrate. Full design:
[warp AI & modelling](warp-ml.md).

**Sub-issues.**
- **F1 — FedAvg counter-weave.** Federated averaging as a coordination-free weave
  over CRDT counters (needs the foundation only).
- **F2 — model-as-wasm-kernel.** The local training step as a shipped kernel (needs
  a C3 wasm runtime — the C2 envelope is the minimum, but running a model *as* a
  kernel needs a runtime to execute it).
- **F3 — secure aggregation** *(optional).*
- **F4 — end-to-end demo.**
- **F-polish — polish pass.**

**Gate.** F1 — none (startable now); F2 and the full F4 demo need a C3 wasm runtime
(the C2 task-descriptor envelope is necessary but not sufficient — a kernel needs a
runtime to run).

**Go/no-go.** F1 alone shows convergent federated averaging on the sim; the full
demo is gated on C landing.

---

## Epic G — `Draft` → DAG; consolidate coordination rounds

**Goal.** Make round *count* an active planner lever. E-3's cost model pins
`rounds` at ≤1 because a `Draft` is a linear pipeline with a single `Embroider`.
Reshape `Draft` into a dependency DAG so independent agreements **batch into one
consensus round** and only value-dependent ones force sequential rounds — *min
rounds = depth of the coordination dependency DAG*. Full design:
[Phase-G spec](superpowers/specs/2026-06-25-warp-phase-g-dag-consolidation-design.md).

**Sub-issues.** G1 — `Draft` → DAG (path-preserving migration, no behaviour
change) · G2 — `Draft.combine` (independent branches) · G3 —
`consolidateEmbroideries` (batch per dependency level) · G4 — cost = DAG-depth +
coupling term · G5 — batched Raft execution · G-polish.

**Gate.** E (done). **Sibling:** native metrics (so G4 emits
`warp.coordination.rounds` live).

**Go/no-go.** G4 (analytical round-count cut on the sim) and G5 (round-count cut
at real-Raft execution). Either failing is a legitimate stop.

---

## Reading this roadmap

- **B is the only commitment.** It completes the foundation's own contract and
  leaves no placeholder in a shipped module. Everything else is laid out, labelled
  exploratory, and waits on its own go/no-go.
- **The gates have loosened** — most of the published DAG's blockers were retired in
  Phase 1, so E and C can open in parallel with B the moment there's capacity.
- **Nothing here changes the experimental posture.** `:kuilt-warp` stays out of the
  BOM and the release until a slice earns its way in.
