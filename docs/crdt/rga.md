# RGA — Replicated Growable Array

An op-based sequence CRDT for ordered collections (chat messages, collaborative text).

## Intuition

Every element gets a globally unique **id** = `(lamportTimestamp, replicaId)`. Insertions say "place this element *after* the element with id X" rather than "place it at index 3" — because index 3 means something different on every replica.

When two replicas independently insert after the same predecessor, they get a deterministic order for free: **larger id wins the slot immediately after the predecessor**. No coordination needed; the rule is baked into the id.

## The id tiebreak rule

Given concurrent `Insert(idA, "a", p)` and `Insert(idB, "b", p)`:

- `idA > idB` → sequence is `… p a b …`
- `idB > idA` → sequence is `… p b a …`

The total order on `RgaId` is: compare Lamport timestamp first; replica string id breaks ties. This makes the merge deterministic and commutative.

## Fits into `Quilted` as op-log union

The "state" is the full set of ops ever applied. `piece` is an idempotent set-union of two op-logs. Set-union satisfies the lattice laws (idempotent, commutative, associative), so the standard `Quilted` interface works for RGA — the ops are the join-semilattice elements. Any two replicas that have absorbed the same set of ops will compute the same `toList()`.

## Tombstone retention

`Remove(id)` adds a tombstone to the op-log; it does **not** delete the element from the log. This is required: a later `Insert(newId, _, removedId)` needs to find the predecessor to place itself correctly. Deleting the removed element would break that.

**GC implication.** Tombstones grow monotonically. A distributed garbage-collection protocol (e.g., stable message delivery epochs, or a coordinated GC wave) would be needed to reclaim them — this is future scope.

## Lamport clock

Each replica maintains a local Lamport counter. Minting a new op increments it. Receiving an op updates the counter to `max(local, received)`. This ensures ids are monotonically increasing across the system even without a global clock.

## Wire transport

Ops are serializable (`@Serializable` on `RgaOp` and `RgaId`). They ride `SeamReplicator` like any other `Quilted<Rga<V>>` — `piece` merges the full op-log and the replicator ships `Delta`s (individual ops wrapped as `Rga` singletons) or `FullState` for new peers.
