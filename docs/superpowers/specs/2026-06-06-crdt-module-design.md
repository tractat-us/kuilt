# `:kuilt-crdt` — a delta-state CRDT zoo

**Status:** design approved, not yet built.
**Date:** 2026-06-06.

## What this is

A new module, `:kuilt-crdt`, that implements a curated tour of Conflict-free
Replicated Data Types as a sibling coordination primitive to `:kuilt-raft`.
Where raft gives a *totally-ordered* replicated log (one leader, strong
consistency), CRDTs give *leaderless eventual consistency*: replicas edit
concurrently and merge deterministically with no coordination. The two are
complementary, not competing.

The driver is **craft and understanding**, not a single consumer need. The goal
is a well-rounded, well-tested set built to the same quality bar as
`:kuilt-raft`, laid out so it can be **built one rung at a time** across many
short, interruptible sessions. A secondary goal is **pedagogical**: each rung
should leave the author with a genuine understanding of the data structure.

## Why CRDTs fit kuilt

kuilt moves opaque frames over *interchangeable, unreliable, partition-prone*
fabrics — WebSocket relay, mDNS LAN, Multipeer, WebRTC, Nearby. **State-based
CRDT merge is robust to dropped, duplicated, and reordered frames by
construction** — exactly kuilt's world. The alternative (op-based / CmRDT) would
force a reliable-causal-broadcast layer that kuilt deliberately does not
promise. That makes a state-based spine the principled choice here, not an
arbitrary one.

## Where CRDTs do and don't fit an application like a turn-based game

A point that shaped the scope. A strictly **turn-based** game's core (turn
order, the shared deck, token pools, the board) is *sequential* — exactly one
actor per turn over shared authoritative state. That is a textbook fit for a
**total order** (raft, or a single authoritative log), **not** CRDTs. Designing
CRDTs for core sequential game rules would be using the wrong tool.

CRDTs earn their place in the **ambient layer** around such a game — state that
is concurrent, low-stakes, and merges cleanly:

- **Presence** — who is currently connected (an OR-Set).
- **Lobby / "ready" toggles** — each peer flips its own bit (a map of registers).
- **Settings negotiation** before start — variant, options (an LWW/OR-Map).
- **Reactions / emotes / chat** — concurrent, append-only (a grow-only log /
  sequence).
- **Spectator cursors** — "looking at item N" (a register per peer).

These motivate which types are worth building first, but the module is a
general-purpose library; no single consumer gates it.

## Architecture

### Convergence style: delta-state from the start

Three convergence styles were considered:

- **State-based (CvRDT)** — replicas exchange whole values; `merge` is a join
  (least-upper-bound): idempotent, commutative, associative. Robust to loss /
  duplication / reordering. Cost: shipping whole state grows unboundedly.
- **Op-based (CmRDT)** — replicas exchange operations; tiny on the wire, but
  requires reliable causal broadcast (exactly-once, causal order) underneath.
- **Delta-state** — state-based *safety* (idempotent join, survives unreliable
  transport) but ships small **deltas** instead of whole state. The modern
  sweet spot; used by systems such as Akka Distributed Data.

This module is **delta-state from the start**. Each operation produces a
**delta** that is itself a small element of the same join-semilattice, so
merging a delta uses the very same `merge`. Delta *is* a state fragment — that
insight keeps delta-state from needing a separate machinery.

**Accepted tradeoff:** delta-state front-loads the foundation. The first ~3 rungs
are foundation investment (interface, dots, DotStore) before the satisfying
concrete types start. Pure state-based would have shipped a counter on day one;
we trade a slower start for a far more powerful, less repetitive middle.

Op-based appears **only** at the one rung where it is genuinely superior — the
sequence / RGA type, whose full state is too large to ship.

### The foundation

- **Join-semilattice interface** — every CRDT is a value with a `merge` that is
  idempotent, commutative, associative. This is what makes the whole module
  robust to kuilt's frame delivery semantics.
- **Delta-mutators** — operations return a *delta* (an element of the same
  lattice), never mutate in place. Merging a delta is the same `merge`.
- **The DotStore framework** — `Dot` (a `(replicaId, seq)` pair), causal context
  (version vector), and three composable causal containers: **DotSet**,
  **DotFun**, **DotMap**. OR-Set, MV-Register, and OR-Map all become *thin
  layers* over these three rather than each reinventing tombstone bookkeeping.
  This is the single highest-leverage thing to build well.

### Naming

The module is `:kuilt-crdt`. kuilt's vocabulary is textile (`Loom` weaves
`Seam`s carrying `Swatch`es), and "kuilt" is literally "quilt" — a whole pieced
from independent **patches**, which *is* the CRDT idea. The metaphor is applied
**on the interface only**:

- a delta is a **`Patch`**,
- the merge operation reads as **`piece()`** (the quilting term for joining
  patches).

Concrete types keep **standard, googleable names** — `GCounter`, `PNCounter`,
`BoundedCounter`, `GSet`, `TwoPhaseSet`, `ORSet`, `LWWRegister`, `MVRegister`,
`LWWMap` / `ORMap`, `Rga`. On-brand spine, recognizable zoo.

### Module placement

- `:kuilt-crdt` — all targets, `explicitApi()` enforced, kotlinx.serialization
  for wire forms (every type must survive becoming a `Swatch`).
- The generic **CRDT law conformance suite** lands in the existing
  `:kuilt-conformance` module, beside `SeamConformanceSuite` and
  `RaftStorageConformanceSuite`.

## The backlog (peelable rungs)

Each rung is **one self-contained PR / sub-issue**: its own acceptance
criterion, its own conformance test, merges independently. The module is in a
coherent, releasable state after every rung. After rung 2, concrete types can be
picked in almost any order.

### Foundation

| # | Rung | Delivers | Dep |
|---|------|----------|-----|
| 0 | Module scaffold + `Quilted` / `Patch` interface + generic CRDT-law conformance suite (idempotent · commutative · associative `piece`, delta-merge equivalence) in `:kuilt-conformance` | the spine every type plugs into | — |
| 1 | Dots + causal context (`Dot`, version vector) | causal plumbing | 0 |
| 2 | DotStore framework — `DotSet` / `DotFun` / `DotMap` | composable causal core | 1 |

### Counters

| # | Rung | Notes | Dep |
|---|------|-------|-----|
| 3 | `GCounter` (delta) | first real type; proves the interface | 0 |
| 4 | `PNCounter` (delta) | two G-Counters composed | 3 |
| 5 | `BoundedCounter` (escrow) | the centerpiece — see below | 4 |

### Sets

| # | Rung | Notes | Dep |
|---|------|-------|-----|
| 6 | `GSet` (delta) | union | 0 |
| 7 | `TwoPhaseSet` | tombstones, and why they leak | 6 |
| 8 | `ORSet` (delta, over DotStore) | add-wins; *presence* | 2 |

### Registers & maps

| # | Rung | Notes | Dep |
|---|------|-------|-----|
| 9 | `LWWRegister` | the clock / timestamp problem | 0 |
| 10 | `MVRegister` (over DotStore) | causality via version vectors | 2 |
| 11 | `LWWMap` / `ORMap` (over DotMap) | *settings, ready toggles* | 2, 9 |

### Systems

| # | Rung | Notes | Dep |
|---|------|-------|-----|
| 12 | Anti-entropy replicator over a `Seam` — delta-buffers, per-neighbor ack/seq, GC, full-state fallback; exposes a converged `StateFlow`. Mirrors `SeamRaftTransport`. | turns pure types into a live thing on kuilt | 0 |
| 13 | `Rga` (sequence, **op-based** — the one place op-based wins) — chat / text ordering | boss level | 12 |

## The escrow counter (rung 5)

Unlike every other type, a bounded counter is **not** just a clever `piece()`.
It is two parts:

- **A pure CRDT part** — a per-replica *quota allocation* table (how much of the
  global budget each replica may currently spend). That table converges by
  normal merge. A local decrement is legal iff this replica still has quota. The
  invariant "never overdraw" holds **without coordination** as long as local
  quota suffices.
- **A protocol part** — when a replica runs low it must *request a transfer* of
  quota from a peer with spare. That rebalancing rides the replicator (rung 12),
  or degrades gracefully to "deny locally until quota arrives."

So rung 5 ships the data type plus local-decrement safety; the *active*
rebalancing is a thin follow-on once rung 12 exists. Even the hard rung stays
peelable. This is the structure that breaks the "CRDTs are just merge functions"
intuition — the one most worth understanding deeply.

## Testing

- **Conformance suite (rung 0)** is **property-based**: for every type, generate
  random op sequences across replicas in random delivery orders and assert
  convergence plus the type's invariant. Reuse whatever property-testing setup
  the in-flight raft property-based-testing work lands.
- **Serialization round-trip** tests per type — it must survive becoming a
  `Swatch`.
- **The replicator (rung 12)** follows the repo coroutine-determinism
  convention: an injected `UnconfinedTestDispatcher(testScheduler)` under
  `runTest`, never a real production dispatcher.

## Understanding each structure

A first-class goal. Every rung's PR carries a concise **explainer** — KDoc plus
a short `docs/crdt/<type>.md` note covering: the intuition, the lattice it lives
in, what `piece()` does, and the one gotcha that makes it tricky. Before *coding*
each rung, walk the structure conceptually first. For the genuinely visual ones
(escrow rebalancing, OR-Set dots, RGA interleaving), use the browser visual
companion to show the merge dynamics rather than describe them.

## Execution

- An **epic issue** plus one ordered **sub-issue per rung**, tracked as a
  checklist on the epic.
- This main checkout is off-limits (active workers in lifecycle/raft-test
  territory). Each rung is built in its **own worktree off fresh `origin/main`**
  — a dispatched `coding-partner` worker or an inline worktree, decided per rung.
- TDD throughout: the failing conformance test is the first commit, the type the
  second.
- Aggressive auto-merge per the repo's pre-1.0 posture. The module is purely
  additive, so it never blocks the `0.3` version line.

## Out of scope (for now)

- Op-based types beyond the single sequence/RGA rung.
- Garbage collection of causal context beyond what the replicator's per-neighbor
  ack tracking provides.
- Persistence of CRDT state (the replicator exposes a `StateFlow`; durable
  storage, if ever needed, is a later concern mirroring `RaftStorage`).
