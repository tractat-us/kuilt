# CRDT zoo

`kuilt-crdt` provides a set of data structures that converge to the same value
across replicas without coordination. Any two replicas that have seen the same
set of updates will agree, regardless of the order those updates arrived.

All types are plain serializable value objects — use them with any transport,
or without a network at all. Add `SeamReplicator` when you want live
propagation over a kuilt `Seam`.

## Pick by use case

| What you're building | Type |
|----------------------|------|
| Chat / shared message log | `Rga` (RGA) — an ordered list where inserts from any peer land in a stable position |
| Collaborative document or JSON config | `JsonCrdt` — recursive JSON with ORMap objects, Rga arrays, and MVRegister leaves |
| User presence, live cursors, typing indicators | `EphemeralMap` — per-replica slots with TTL; departed peers expire automatically |
| Tags, followers, members, shopping cart | `ORSet` — add/remove set with add-wins on concurrent edits |
| User settings or feature flags | `LWWMap` — last-writer-wins map; each key converges to the most-recent write |
| Page views, upvotes, event counts | `GCounter` — grow-only counter; every replica contributes its own tally |
| Like/dislike counts, inventory deltas | `PNCounter` — positive and negative increments, one slot per replica |
| Rate limiting, resource allocation, load management | `BoundedCounter` — counter with a cap that can only be raised by rebalancing |
| Audit trail, append-only event log | `GSet` — grow-only set; elements are never removed |
| One field edited by many (show conflicts) | `MVRegister` — retains all concurrent values until one replica resolves them |

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

## Live replication

`SeamReplicator<S>` runs over a `Seam` and keeps a CRDT replica live: it ships deltas to all peers as you apply updates, and merges inbound deltas as they arrive. `state` is a `StateFlow<S>` — always the current converged value.

See [SeamReplicator](crdt-seamreplicator.md) for usage and the `MuxSeam` multiplexing pattern that lets multiple replicators share one transport.

## Serialization

Every CRDT type is `@Serializable`. Wire transport (CBOR by default, via `SeamReplicator`) and JSON round-trips both work. Each type's serializer is accessible via `T.serializer()` or `T.serializer(elementSerializer)`.
