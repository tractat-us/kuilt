# CRDT zoo

`kuilt-crdt` provides a set of **delta-state CRDTs** — data structures that converge to the same value across replicas without coordination. Any two replicas that have seen the same set of updates will agree, regardless of the order those updates arrived.

## The `Quilted` interface

Every CRDT implements `Quilted<S>`:

```kotlin
interface Quilted<S : Quilted<S>> {
    fun piece(other: S): S  // merge — idempotent, commutative, associative
}
```

`piece` is the join in the join-semilattice. Calling it with the same argument twice produces the same result as calling it once (idempotent). Order doesn't matter (commutative). Multiple calls can be grouped in any order (associative). These three laws guarantee convergence.

**Delta state.** Instead of shipping the entire current state on every update, CRDTs here emit a *delta* — a minimal patch that represents only what changed. Merging a delta into the current state advances it the same way merging the full state would. `SeamReplicator` exploits this to send small delta messages over the wire and ship the full state only to late joiners.

## The `Patch` wrapper

Mutations return a `Patch<S>` rather than directly mutating state. Applying the patch to the current state produces the next state:

```kotlin
val counter = GCounter.ZERO
val delta: GCounter = counter.inc(replica, 3L)  // a delta, not the full state
val next: GCounter = counter.piece(delta)        // apply the delta
```

## Structure at a glance

| Group | Types | Convergence property |
|-------|-------|----------------------|
| Counters | `GCounter`, `PNCounter`, `BoundedCounter` | Monotone integer; element-wise max per replica slot |
| Sets | `GSet`, `ORSet`, `TwoPhaseSet` | Set union / observe-remove semantics |
| Registers | `LWWRegister`, `MVRegister` | Last-write-wins or multi-value concurrent conflict |
| Maps | `LWWMap`, `ORMap` | Key-level LWW or ORSet-keyed map |
| Sequences | `Rga` | Ordered list with stable unique ids |
| Composite | `JsonCrdt` | Recursive JSON document — ORMap objects, Rga arrays, MVRegister leaves |
| Ephemeral | `EphemeralMap` | Per-replica presence slot, clock-ordered, with caller-driven TTL eviction |
| Causal primitives | `Causal`, `DotContext`, `DotSet` | Causal-context-based remove/add reasoning |

## Live replication

`SeamReplicator<S>` runs over a `Seam` and keeps a CRDT replica live: it ships deltas to all peers as you apply updates, and merges inbound deltas as they arrive. `state` is a `StateFlow<S>` — always the current converged value.

See [SeamReplicator](crdt-seamreplicator.md) for usage and the `MuxSeam` multiplexing pattern that lets multiple replicators share one transport.

## Serialization

Every CRDT type is `@Serializable`. Wire transport (CBOR by default, via `SeamReplicator`) and JSON round-trips both work. Each type's serializer is accessible via `T.serializer()` or `T.serializer(elementSerializer)`.
