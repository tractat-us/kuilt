# TwoPhaseSet

A two-phase set: elements can be added once and removed once. Removal is permanent — once an element is tombstoned, it can never be re-added. **Remove wins** over a concurrent re-add.

**Converges to:** the set of elements that have been added and not yet tombstoned, where tombstones are permanent.

## Merge rule

A `TwoPhaseSet` maintains two `GSet`s: an add-set `A` and a tombstone-set `R`. An element is present if `element ∈ A ∧ element ∉ R`. Merge is union of both sets independently. Because tombstones only grow, a tombstoned element remains absent in all future states — even if another replica concurrently adds it.

## Code examples

**Add and remove:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt#addThenContains / removeMakesAbsent -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt
// Tests: addThenContains / removeMakesAbsent
val s = TwoPhaseSet.empty<String>().let { it.piece(it.add("x")) }
assertTrue(s.contains("x"))

val gone = s.piece(s.remove("x"))
assertFalse(gone.contains("x"))
```

**Remove wins over concurrent re-add:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt#removeWinsOverConcurrentReAdd -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt
// Test: removeWinsOverConcurrentReAdd
val start = TwoPhaseSet.empty<String>().let { it.piece(it.add("x")) }
val alice = start.piece(start.remove("x"))
val bob = start.piece(start.add("x"))
assertFalse(alice.piece(bob).contains("x"))  // tombstone wins forever
```

**Tombstone is permanent — resurrection is impossible:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt#cannotResurrectARemovedElement -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt
// Test: cannotResurrectARemovedElement
val s = TwoPhaseSet.empty<String>()
    .let { it.piece(it.add("x")) }
    .let { it.piece(it.remove("x")) }
val retried = s.piece(s.add("x"))
assertFalse(retried.contains("x"))
```

## When to use

`TwoPhaseSet` is appropriate when removal semantics must be final — expired tokens, retired identifiers, blacklisted entries. For a set where removed elements can be re-added, use [ORSet](crdt-orset.md).
