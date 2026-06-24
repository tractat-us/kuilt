# RGA (`Rga`)

An ordered list for collaborative text and sequences. Every element gets a stable unique id, so concurrent insertions from different devices always resolve to the same final order — no index collisions, no lost edits.

**Converges to:** the same ordered sequence on every replica, regardless of the order concurrent insertions and removals arrived.

## The key idea

Every element gets a globally unique id = `(lamportTimestamp, replicaId)`. Insertions say "place this element *after* element with id X" rather than "place it at index 3" — because index 3 means something different on every replica. When two replicas independently insert after the same predecessor, they get a deterministic order: **larger id wins the slot immediately after the predecessor**.

## Tombstone-based removal

`remove(id)` adds a tombstone to the op-log; it does not delete the element's op. This is required: a later `insert(newId, after = removedId)` needs to find the predecessor to place itself correctly. Deleting the removed element would break that insertion.

`toList()` renders only non-tombstoned elements in sequence order.

## History windowing

For long-running sequences (e.g. a chat log), the `RgaGcCoordinator` + `WindowPolicy.byCount(n)` combination drops the leading visible prefix beyond `n` elements via a `Compact` operation. The op-log is bounded to the window size:

```kotlin
// see RgaWindowByCountIntegrationTest.byCountDropsLeadingPrefixAndWindowRenders
val coordinator = RgaGcCoordinator(seam, WindowPolicy.byCount(100))
coordinator.start()
// WindowPolicy trims the leading visible prefix beyond 100 elements;
// a late joiner receives the windowed FullState and never materialises the dropped prefix.
```

A late joiner receives the windowed state via `FullState` and never materialises the dropped prefix — the op-log stays bounded.

## Causal stability and GC

Tombstones accumulate over time. `RgaGcCoordinator` implements a distributed garbage-collection protocol: once a tombstone is *causally stable* (every peer has seen the remove op), the coordinator emits a `Compact` patch that evicts the tombstone from the op-log. This keeps the op-log bounded under continuous insert/remove workloads.

See `docs/adr-003-rga-tombstone-gc-history-windowing.md` in the repository for the full design.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleRga -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val (rgaA, opA) = Rga.empty<String>().insertAt(a, 0, "Hello")
val (rgaB, opB) = Rga.empty<String>().insertAt(b, 0, "World")

// Both replicas absorb both ops.
val mergedByA = rgaA.apply(opB)
val mergedByB = rgaB.apply(opA)

// Convergence: both produce the same list regardless of delivery order.
check(mergedByA.toList() == mergedByB.toList())
```

## When to use

Use `Rga` (RGA) for ordered sequences where concurrent insertions and deletions are possible — chat messages, command history, collaborative text. For unordered collections, prefer [ORSet](crdt-orset.md) or [GSet](crdt-gset.md). For collaborative text where concurrent runs must not interleave, see [Fugue](crdt-fugue.md).
