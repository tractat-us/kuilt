# Module kuilt-crdt

A dependency-free delta-state CRDT zoo. Provides `Quilted<S>` value types only —
no transport dependency. Live replication over a `Seam` lives in `:kuilt-quilter`.

## The zoo

Every type implements `Quilted<S>`, a join-semilattice whose `piece` satisfies the
three lattice laws (idempotent, commutative, associative). Mutations return a
`Patch` — a small state fragment any replica absorbs with `piece`. Replicas that
have absorbed the same set of patches converge to the same value regardless of
delivery order or duplication.

| Type | Convergence rule | Remove? |
|------|-----------------|---------|
| `GCounter` | Sum of per-replica maxima | No |
| `PNCounter` | Two GCounters: inc − dec | No |
| `GSet` | Set union | No |
| `TwoPhaseSet` | Union; tombstones win permanently | Once |
| `ORSet` | Causal: add-wins over concurrent remove | Yes |
| `LWWRegister` | Largest `(timestamp, replicaId)` tag wins | Via overwrite |
| `MVRegister` | Retain all concurrent writes | Via supersede |
| `LWWMap` | Per-key LWWRegister | Via overwrite |
| `ORMap` | Per-key add-wins; values merge via `piece` | Yes |
| `BoundedCounter` | Escrow-quota counter; spend within quota | Via transfer |
| `Rga` | Op-log union; Lamport-ordered insert wins | Via tombstone |
| `JsonCrdt` | Recursive JSON: ORMap objects, Rga arrays, MVRegister leaves | Via key remove |
| `EphemeralMap` | Per-replica slot, higher clock wins; caller-driven TTL eviction | Via graceful leave |
| `MovableTree` | Op-log union; Lamport-ordered replay with cycle prevention | Via reparent |

## Replication

Live replication is in `:kuilt-quilter`. `Quilter<S>` streams deltas over a `Seam`
and converges any `Quilted` state across peers automatically.

`MuxSeam` (`:kuilt-core`) multiplexes several CRDTs over one underlying `Seam`,
routing frames by channel tag.

## The `Causal` layer

`ORSet`, `MVRegister`, and `ORMap` are all thin wrappers over `Causal<DotStore>`.
A `DotContext` accumulates witnessed `(ReplicaId, seq)` dots; a remove drops the
dots currently on the element from the store while retaining them in the context,
so a concurrent add on a replica that minted a *different* dot survives the merge.
