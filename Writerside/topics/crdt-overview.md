# Replication

Replication keeps your app's shared state — counters, lists, maps, documents — in sync across devices, even when people edit at the same time or spend time offline.
`kuilt-crdt` gives you data structures that merge those edits automatically, so no change is quietly lost.

Think of each update as a quilt patch: patches can arrive in different orders and still sew into the same final pattern.

The technical name for these structures is **CRDTs** (Conflict-free Replicated Data Types): any two peers that have seen the same set of updates will always hold the same value, regardless of arrival order.

Use replication when you want convergence without a central coordinator. If your feature needs strict, globally ordered decisions instead (like turn order in a game), use [Consensus](raft.md) for that part.

`kuilt-crdt` provides nineteen types, grouped by what they model.

**`kuilt-crdt` depends on nothing else in kuilt** — not even `kuilt-core`. Add it to any project on its own and replicate over whatever transport you already have. Live replication over a `Seam` (via [`Quilter`](crdt-quilter.md)) is opt-in, not required.

## Pick by what you're building

| What you're building | Type |
|---|---|
| Event tally, vote count, metric | [`GCounter`](crdt-gcounter.md) — grow-only; each replica owns its slot |
| Like/dislike, upvote/downvote | [`PNCounter`](crdt-pncounter.md) — increment and decrement; value may be negative |
| Score or tally that anyone can reset to zero | [`ResettableCounter`](crdt-resettablecounter.md) — concurrent increments survive a reset |
| Collaborative tag cloud, ever-growing log | [`GSet`](crdt-gset.md) — add-only; simple and fast |
| Collaborative labels where items can be archived | [`TwoPhaseSet`](crdt-twophaseset.md) — add once, remove once; removal is permanent |
| Collaborative labels where items can be re-added | [`ORSet`](crdt-orset.md) — add-wins on concurrent conflict |
| Shared status field (last writer wins) | [`LWWRegister`](crdt-lwwregister.md) — concurrent writes silently resolve by timestamp |
| Shared status field (surface conflicts) | [`MVRegister`](crdt-mvregister.md) — concurrent writes surface as multiple values; caller resolves |
| Online presence / roster (key → last-write value) | [`LWWMap`](crdt-lwwmap.md) — per-key LWWRegister |
| Presence / roster (key → nested CRDT value) | [`ORMap`](crdt-ormap.md) — add-wins key set; values merge via their own CRDT |
| Seat or inventory reservation (can't oversell) | [`BoundedCounter`](crdt-bounded-counter.md) — quota-per-replica; total spend can never exceed budget |
| Collaborative text / ordered list (non-interleaving) | [`Fugue`](crdt-fugue.md) — concurrent runs stay contiguous; maximal non-interleaving proven |
| Collaborative text / ordered list | [`Rga`](crdt-rga.md) — stable insertion ids; converges to same order everywhere; GC support |
| Hierarchical data (file trees, scene graphs) | [`MovableTree`](crdt-movabletree.md) — reparent nodes concurrently; cycle prevention guaranteed |
| JSON document sync | [`JsonCrdt`](crdt-jsoncrdt.md) — ORMap objects + RGA arrays + MVRegister leaves, recursive |
| Ephemeral presence (cursors, typing indicators) | [`EphemeralMap`](crdt-ephemeralmap.md) — per-replica slot; TTL eviction |
| "Have I seen this?" deduplication, compact | [`BloomFilter`](crdt-bloomfilter.md) — probabilistic membership; no false negatives; bitwise-OR merge |
| How many distinct items (e.g. unique visitors)? | [`HyperLogLog`](crdt-hyperloglog.md) — ~1% error estimate; 16 KB for any cardinality |
| How often does X appear (trending topics, heavy hitters)? | [`CountMinSketch`](crdt-countminsketch.md) — frequency sketch; never underestimates; fixed memory |
| Causal stability / building your own CRDT | [`Causal` primitives](crdt-causal.md) — `DotContext`, `DotSet`, `DotFun`, `DotMap` |

## Structure at a glance

| Group | Types | Convergence property |
|-------|-------|----------------------|
| Counters | `GCounter`, `PNCounter`, `BoundedCounter`, `ResettableCounter`, `HyperLogLog`, `CountMinSketch` | Per-replica monotone internals; exact integer result — or a fixed-memory probabilistic estimate for the two sketches (`HyperLogLog` distinct-count, `CountMinSketch` frequency) |
| Sets | `GSet`, `ORSet`, `TwoPhaseSet`, `BloomFilter` | Set union / observe-remove semantics; `BloomFilter` is a compact probabilistic membership set (no false negatives) |
| Registers | `LWWRegister`, `MVRegister` | Last-write-wins or multi-value concurrent conflict |
| Maps | `LWWMap`, `ORMap` | Key-level LWW or ORSet-keyed map |
| Sequences | `Fugue`, `Rga` | Ordered list with stable unique ids; Fugue adds maximal non-interleaving |
| Trees | `MovableTree` | Op-log union; Lamport-ordered replay with cycle prevention |
| Composite | `JsonCrdt` | Recursive JSON document — ORMap objects, RGA arrays, MVRegister leaves |
| Ephemeral | `EphemeralMap` | Per-replica presence slot, clock-ordered, with caller-driven TTL eviction |
| Causal primitives | `Causal`, `DotContext`, `DotSet` | Causal-context-based remove/add reasoning |

## Using the types without kuilt

These types are plain serializable value objects, and **`kuilt-crdt` declares no dependency on `kuilt-core` or any other kuilt module** — you can add it to a project entirely on its own. You do not need a `Seam`, a `Loom`, or any other kuilt module to use them. Apply updates by calling `.piece()` directly; serialize with `kotlinx.serialization`; ship the bytes over any transport you already have.

```kotlin
var counter = GCounter.ZERO
val delta = counter.inc(myReplicaId, 1L)   // a delta — not the full state
counter = counter.piece(delta)             // apply locally
// ship `delta` to peers; they apply it with their own .piece(delta)
```

`Quilter` automates this over a kuilt `Seam` — but it is optional. If you already have a messaging layer, wire the types to it yourself.

## Live replication

`Quilter<S>` runs over a `Seam` and keeps one replica live: it ships deltas to all peers as you apply updates, and merges inbound deltas as they arrive. `state` is a `StateFlow<S>` — always the current converged value.

See [Quilter](crdt-quilter.md) for usage and the `MuxSeam` multiplexing pattern that lets multiple replicators share one transport.

## Serialization

Every CRDT type is `@Serializable`. Wire transport (CBOR by default, via `Quilter`) and JSON round-trips both work. Each type's serializer is accessible via `T.serializer()` or `T.serializer(elementSerializer)`.

## How it works (CRDT basics)

### The `Quilted` interface

Every CRDT implements `Quilted<S>`:

```kotlin
interface Quilted<S : Quilted<S>> {
    fun piece(other: S): S  // merge — idempotent, commutative, associative
}
```

`piece` is the join in the join-semilattice. Calling it with the same argument twice produces the same result as calling it once (idempotent). Order doesn't matter (commutative). Multiple calls can be grouped in any order (associative). These three laws guarantee convergence.

**Delta state.** Instead of shipping the entire current state on every update, CRDTs here emit a *delta* — a minimal patch that represents only what changed. Merging a delta into the current state advances it the same way merging the full state would. `Quilter` exploits this to send small delta messages over the wire and ship the full state only to late joiners.

### The `Patch` wrapper

Mutations return a delta (a value of the same CRDT type) rather than directly mutating state. Apply it with `piece`:

```kotlin
val counter = GCounter.ZERO
val delta: GCounter = counter.inc(replica, 3L)  // a delta, not the full state
val next: GCounter = counter.piece(delta)        // apply the delta
```
