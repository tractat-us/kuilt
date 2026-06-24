# ORSet

A set where elements can be added and removed. When two devices edit at the same time — one adds an item, another removes it — **add wins**. The item stays in.

**Converges to:** a set containing exactly the elements whose add operations have not been *causally dominated* by a subsequent remove from a replica that observed that add.

## Merge rule

Every `add(replica, element)` tags the element with a unique dot `(replica, counter)`. A `remove(element)` witnesses all dots currently associated with the element in the local state and marks them as removed. On merge:

- A dot present in only one replica's store (the other never saw it) is kept.
- A dot present in the store of A but in the causal context of B (B saw it and removed it) is dropped.

This is why add wins over a *concurrent* remove: a concurrent remove only witnessed dots it already had. A new dot minted by the concurrent add was never seen by the remover, so it survives.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSet -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Shared start: "alice" is present on both replicas.
val start = ORSet.empty<String>().add(a, "alice")

val alice = start.remove("alice")       // Alice concurrently removes
val bob = start.add(b, "alice")         // Bob concurrently re-adds

val merged = alice.piece(bob)
check(merged.contains("alice"))         // add-wins
```

## When to use

`ORSet` is the right choice for most concurrent set workloads where elements can be re-added after removal. Add-wins semantics are intuitive: a concurrent re-add "undoes" a concurrent remove. For permanent removal (tombstone-wins), see [TwoPhaseSet](crdt-twophaseset.md).
