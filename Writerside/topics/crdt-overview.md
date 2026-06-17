# Conflict-free Replicated Data Types (CRDTs)

If you're using a fabric, your real goal is to share application data across peers without making users think about sync. Counters should stay correct, lists should keep their order, and maps should settle to the same value everywhere — even when devices are offline, messages arrive late, or updates race.

That is exactly what the types in `kuilt-crdt` are for.

Formally, these are **C**onflict-free **R**eplicated **D**ata **T**ypes (CRDTs): data structures that replicas can update independently and later merge deterministically. If two peers have seen the same set of updates, they converge to the same value regardless of update order.

`kuilt-crdt` provides fourteen types, grouped by what they model.

## Pick by what you're building

| What you're building | Type |
|---|---|
| Event tally, vote count, metric | [`GCounter`](crdt-gcounter.md) — grow-only; each replica owns its slot |
| Like/dislike, upvote/downvote | [`PNCounter`](crdt-pncounter.md) — increment and decrement; never goes negative in aggregate |
| Collaborative tag cloud, ever-growing log | [`GSet`](crdt-gset.md) — add-only; simple and fast |
| Collaborative labels where items can be archived | [`TwoPhaseSet`](crdt-twophaseset.md) — add once, remove once; removal is permanent |
| Collaborative labels where items can be re-added | [`ORSet`](crdt-orset.md) — add-wins on concurrent conflict |
| Shared status field (last writer wins) | [`LWWRegister`](crdt-lwwregister.md) — concurrent writes silently resolve by timestamp |
| Shared status field (surface conflicts) | [`MVRegister`](crdt-mvregister.md) — concurrent writes surface as multiple values; caller resolves |
| Online presence / roster (key → last-write value) | [`LWWMap`](crdt-lwwmap.md) — per-key LWWRegister |
| Presence / roster (key → nested CRDT value) | [`ORMap`](crdt-ormap.md) — add-wins key set; values merge via their own CRDT |
| Seat or inventory reservation (can't oversell) | [`BoundedCounter`](crdt-bounded-counter.md) — quota-per-replica; total spend can never exceed budget |
| Collaborative text / ordered list | [`Rga`](crdt-rga.md) — stable insertion ids; converges to same order everywhere |
| JSON document sync | [`JsonCrdt`](crdt-jsoncrdt.md) — ORMap objects + Rga arrays + MVRegister leaves, recursive |
| Ephemeral presence (cursors, typing indicators) | [`EphemeralMap`](crdt-ephemeralmap.md) — per-replica slot; TTL eviction |
| Causal stability / building your own CRDT | [`Causal` primitives](crdt-causal.md) — `DotContext`, `DotSet`, `DotFun`, `DotMap` |

## Structure at a glance

| Group | Types | Convergence property |
|-------|-------|----------------------|
| Counters | `GCounter`, `PNCounter`, `BoundedCounter` | Monotone integer; element-wise max per replica slot |
| Sets | `GSet`, `ORSet`, `TwoPhaseSet` | Set union / observe-remove semantics |
| Registers | `LWWRegister`, `MVRegister` | Last-write-wins or multi-value concurrent conflict |
| Maps | `LWWMap`, `ORMap` | Key-level LWW or ORSet-keyed map |
| Sequences | `Rga` (RGA) | Ordered list with stable unique ids |
| Composite | `JsonCrdt` | Recursive JSON document — ORMap objects, Rga arrays, MVRegister leaves |
| Ephemeral | `EphemeralMap` | Per-replica presence slot, clock-ordered, with caller-driven TTL eviction |
| Causal primitives | `Causal`, `DotContext`, `DotSet` | Causal-context-based remove/add reasoning |

## Using the types without kuilt

These types are plain serializable value objects. You do not need a `Seam`, a `Loom`, or any other kuilt module to use them. Apply updates by calling `.piece()` directly; serialize with `kotlinx.serialization`; ship the bytes over any transport you already have.

```kotlin
var counter = GCounter.ZERO
val delta = counter.inc(myReplicaId, 1L)   // a delta — not the full state
counter = counter.piece(delta)             // apply locally
// ship `delta` to peers; they apply it with their own .piece(delta)
```

`SeamReplicator` automates this over a kuilt `Seam` — but it is optional. If you already have a messaging layer, wire the types to it yourself.

## Live replication

`SeamReplicator<S>` runs over a `Seam` and keeps one replica live: it ships deltas to all peers as you apply updates, and merges inbound deltas as they arrive. `state` is a `StateFlow<S>` — always the current converged value.

See [SeamReplicator](crdt-seamreplicator.md) for usage and the `MuxSeam` multiplexing pattern that lets multiple replicators share one transport.

## Serialization

Every CRDT type is `@Serializable`. Wire transport (CBOR by default, via `SeamReplicator`) and JSON round-trips both work. Each type's serializer is accessible via `T.serializer()` or `T.serializer(elementSerializer)`.

## How it works under the hood

### The `Quilted` interface

Every CRDT implements `Quilted<S>`:

```kotlin
interface Quilted<S : Quilted<S>> {
    fun piece(other: S): S  // merge — idempotent, commutative, associative
}
```

`piece` is the join in the join-semilattice. Calling it with the same argument twice produces the same result as calling it once (idempotent). Order doesn't matter (commutative). Multiple calls can be grouped in any order (associative). These three laws guarantee convergence.

**Delta state.** Instead of shipping the entire current state on every update, CRDTs here emit a *delta* — a minimal patch that represents only what changed. Merging a delta into the current state advances it the same way merging the full state would. `SeamReplicator` exploits this to send small delta messages over the wire and ship the full state only to late joiners.

### The `Patch` wrapper

Mutations return a delta (a value of the same CRDT type) rather than directly mutating state. Apply it with `piece`:

```kotlin
val counter = GCounter.ZERO
val delta: GCounter = counter.inc(replica, 3L)  // a delta, not the full state
val next: GCounter = counter.piece(delta)        // apply the delta
```
