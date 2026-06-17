# CRDT zoo

`kuilt-crdt` provides a set of **delta-state CRDTs** — data structures that converge to the same value across replicas without coordination. Pick by what you're building:

## Pick by use case

| What you're building | Type | Notes |
|---|---|---|
| Event tally, vote count, metric | [`GCounter`](crdt-gcounter.md) | Grow-only; each replica owns its slot |
| Like/dislike, upvote/downvote | [`PNCounter`](crdt-pncounter.md) | Increment and decrement; never goes negative in aggregate |
| Collaborative tag cloud, ever-growing log | [`GSet`](crdt-gset.md) | Add-only; simple and fast |
| Collaborative labels where items can be archived | [`TwoPhaseSet`](crdt-twophaseset.md) | Add once, remove once; removal is permanent |
| Collaborative labels where items can be re-added | [`ORSet`](crdt-orset.md) | Add-wins on concurrent conflict |
| Shared status field (last writer wins) | [`LWWRegister`](crdt-lwwregister.md) | Concurrent writes silently resolve by timestamp |
| Shared status field (surface conflicts) | [`MVRegister`](crdt-mvregister.md) | Concurrent writes surface as multiple values; caller resolves |
| Online presence / roster (key → last-write value) | [`LWWMap`](crdt-lwwmap.md) | Per-key LWWRegister |
| Presence / roster (key → nested CRDT value) | [`ORMap`](crdt-ormap.md) | Add-wins key set; values merge via their own CRDT |
| Seat or inventory reservation (can't oversell) | [`BoundedCounter`](crdt-bounded-counter.md) | Quota-per-replica; total spend can never exceed budget |
| Collaborative text / ordered list | [`Rga`](crdt-rga.md) | Stable insertion ids; converges to same order everywhere |
| JSON document sync | [`JsonCrdt`](crdt-jsoncrdt.md) | ORMap objects + Rga arrays + MVRegister leaves, recursive |
| Ephemeral presence (cursors, typing indicators) | [`EphemeralMap`](crdt-ephemeralmap.md) | Per-replica slot; TTL eviction |
| Causal stability / building your own CRDT | [`Causal` primitives](crdt-causal.md) | `DotContext`, `DotSet`, `DotFun`, `DotMap` |

## Live replication

`SeamReplicator<S>` runs over a `Seam` and keeps a CRDT replica live: it ships deltas to all peers as you apply updates, and merges inbound deltas as they arrive. `state` is a `StateFlow<S>` — always the current converged value.

See [SeamReplicator](crdt-seamreplicator.md) for usage and the `MuxSeam` multiplexing pattern that lets multiple replicators share one transport.

## Serialization

Every CRDT type is `@Serializable`. Wire transport (CBOR by default, via `SeamReplicator`) and JSON round-trips both work.

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
