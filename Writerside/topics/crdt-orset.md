# ORSet

An observe-remove set. Elements can be added and removed. When an add and a remove happen concurrently, **add wins**.

**Converges to:** a set containing exactly the elements whose add operations have not been *causally dominated* by a subsequent remove from a replica that observed that add.

## Merge rule

Every `add(replica, element)` tags the element with a unique dot `(replica, counter)`. A `remove(element)` witnesses all dots currently associated with the element in the local state and marks them as removed. On merge:

- A dot present in only one replica's store (the other never saw it) is kept.
- A dot present in the store of A but in the causal context of B (B saw it and removed it) is dropped.

This is why add wins over a *concurrent* remove: a concurrent remove only witnessed dots it already had. A new dot minted by the concurrent add was never seen by the remover, so it survives.

## Code examples

**Add then contains:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORSetTest.kt#addThenContains -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORSetTest.kt
// Test: addThenContains
val s = ORSet.empty<String>().add(a, "card")
assertTrue(s.contains("card"))
assertEquals(setOf("card"), s.elements)
```

**Add wins over concurrent remove:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORSetTest.kt#addWinsOverConcurrentRemove -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORSetTest.kt
// Test: addWinsOverConcurrentRemove
val start = ORSet.empty<String>().add(a, "card")
val alice = start.remove("card")     // Alice removes what she saw
val bob = start.add(b, "card")       // Bob concurrently re-adds (new dot)
val merged = alice.piece(bob)
assertTrue(merged.contains("card"))  // add wins
```

**Remove wins when it observed the add:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORSetTest.kt#removeWinsWhenNothingConcurrentlyAdded -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORSetTest.kt
// Test: removeWinsWhenNothingConcurrentlyAdded
val start = ORSet.empty<String>().add(a, "card")
val alice = start.remove("card")
val merged = alice.piece(start)     // Bob did nothing new
assertFalse(merged.contains("card"))
```

## When to use

`ORSet` is the right choice for most concurrent set workloads where elements can be re-added after removal. Add-wins semantics are intuitive: a concurrent re-add "undoes" a concurrent remove. For permanent removal (tombstone-wins), see [TwoPhaseSet](crdt-twophaseset.md).
