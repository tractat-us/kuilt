# Performance & scaling — design spec

> Status: planning artifact for the **performance epic**. Captures the design
> agreed during brainstorming on 2026-06-21. The harness it describes is the
> durable deliverable; the optimization fixes are tracked as sibling sub-issues.

## Why

kuilt is correctness-first and pre-1.0. A read of the data path, the CRDT zoo,
and Raft found the library is **not** slow in any pathological way — frames
cross every layer as raw bytes with a small bounded number of copies, the causal
`DotContext` is compacted (no unbounded dot growth), and Raft's steady-state
message complexity is optimal at `2(N-1)` per committed entry. But a few concrete
optimizations were left on the table, and there is **no way to observe how the
zoo and Raft behave as the peer count climbs**. This epic adds that observability
as a reusable harness, then lands the optimizations the harness can measure.

A second motivation: a fully-connected mesh costs `O(N²)` connections and, for
some workloads (notably `BoundedCounter` rebalancing), `O(N²)` messages per
event. A harness that measures message complexity across topologies is the
evidence base for a future **partially-connected mesh** — that design is a
*finding* this epic enables, not work it performs.

## Non-goals

- Designing or building a partial-mesh topology. The harness makes it cheap to
  *compare* topologies; choosing one is downstream.
- Wiring real-TCP scaling runs into the default CI gate. They are real-IO /
  real-time and stay opt-in (see below).
- Micro-tuning constant factors beyond the four named fixes.

## The harness (foundational sub-issue)

A new **`:kuilt-scale`** module (JVM-focused, **not published** — a test/bench
surface, excluded from the `kmp-library` publish set). Two layers over one
metrics model.

### Metrics model

A thin instrumentation wrapper at the `Seam` layer that counts, per logical
operation: **broadcasts**, **`sendTo`s**, **bytes on the wire**, and a
**convergence detector** (all nodes reach an identical observable state, or an
identical Raft commit index). Convergence is reported in **logical rounds** for
the deterministic layer and in **wall-clock** for the real-TCP layer. The same
collector backs both layers so their numbers line up.

### Layer A — deterministic, in-memory (CI-gateable)

- Builds an **N-node fully-connected mesh** over channel-backed in-memory
  connections, driven under `StandardTestDispatcher` virtual time.
- Measures **message counts and convergence rounds as functions of N** — pure
  counts, no wall-clock — so the results are deterministic and usable as
  **regression guards** in CI (e.g. assert Raft commits in exactly `2(N-1)`
  messages/entry; assert today's `BoundedCounter` transfer is `O(N²)` and, after
  the fix, `O(N)`).
- Raft scaling tests **reuse the canonical `RaftSimulation` /
  `InMemoryRaftNetwork` / `MultiNodeRaftSim` harness** extended to higher N —
  never a hand-rolled cluster (a hand-rolled done-when loop spins the scheduler
  CPU-bound and hangs, per repo discipline). Tight `runTest` timeout, bounded
  time-advance, seeded per-node election RNG.

### Layer B — real localhost TCP (opt-in)

- Assembles `N·(N-1)/2` `TcpLoom` 2-peer links into per-node `meshSeam`s
  (JVM/Android only, where `:kuilt-tcp` lives). This is the "low enough cost to
  run locally" surface the epic was motivated by.
- **`-P`-gated** via `providers.gradleProperty("scale.tcp.tests").orNull` →
  `systemProperty`, exactly like the existing `mdns.multicast.tests` suite, and
  **never in the default CI gate**.
- Sweeps N over a small range (e.g. 3, 5, 7, 10, 15, 20), reports a table of
  wall-clock convergence + actual socket/fd counts, and **validates that Layer A's
  predictions hold over real sockets** (same Big-O, real constant factors).

### Topology-pluggable

The mesh builder takes a **topology** parameter — complete graph first, with
ring / k-regular / star-core as drop-in alternatives — so the partial-mesh
comparison is a few lines, not a rewrite. The cross-topology message-complexity
table is the headline output that informs future topology work.

## Optimization fixes (sibling sub-issues)

1. **Raft O(1) log access.** Replace `entryAt`'s `firstOrNull { it.index == … }`
   and the per-peer-per-heartbeat `log.filter { it.index >= ni }` with index
   arithmetic on the contiguous log (`log[(index - log.first().index)]`) and a
   `subList` slice. Highest-leverage, mechanical, guarded by a scaling assertion
   from the harness.
2. **RGA cache-threading.** Thread `insertsById` / `nextSeq` / lamport state
   forward across `insertAfter` / `apply` / `piece` instead of discarding the
   per-instance caches on every op; make `size` read the cached `sequence`
   instead of rebuilding via `toList()`. Removes RGA's `O(ops)`-per-op cliff
   (the only structure in the zoo with a real scaling cliff).
3. **BoundedCounter rebalancing (design-first, `needs-design`).** The current
   reactive protocol — broadcast a request, every surplus peer donates
   unilaterally, each donation broadcasts a delta that everyone acks — costs
   `O(N²)` messages per low-quota event on a full mesh. Replace it with a
   smarter scheme grounded in the escrow / demarcation literature (e.g. proactive
   gossip rebalancing toward demand-weighted shares, with on-demand *targeted*
   borrow as the fast path). Carries its own mini-spec before code; the harness
   provides before/after evidence.
4. **Mux zero-copy receive.** Add an offset-view `Swatch` (payload + offset +
   length) so `MuxSeam` / `NamedMux` stop `copyOfRange`-ing every received frame
   to strip their header. Touches the core contract, so it carries
   `SeamConformanceSuite` coverage. Lowest priority of the four.

## Sequencing

- **First wave (parallel, orthogonal modules):** harness foundation
  (`:kuilt-scale`), Raft O(1) log (`:kuilt-raft`), RGA (`:kuilt-crdt`), mux
  zero-copy (`:kuilt-core`).
- **Second wave (after the harness merges):** Raft scaling tests + zoo scaling
  tests, in parallel against the merged harness API.
- **BoundedCounter:** literature research → mini-spec → implementation.

## Acceptance

- `:kuilt-scale` builds and is excluded from publication.
- Layer A produces deterministic message-count / round-count numbers for Raft and
  for a representative zoo workload, with at least one assertion wired as a
  regression guard.
- Layer B runs under `-Pscale.tcp.tests=true`, stands up a fully-connected TCP
  mesh up to the swept N, and reports a convergence + socket-count table.
- Each optimization fix lands with a harness-measured before/after.
