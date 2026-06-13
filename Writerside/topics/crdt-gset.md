# GSet

A grow-only set. Elements can be added but never removed. Merge is set union.

**Converges to:** the union of all elements ever added across all replicas.

## Merge rule

`piece(a, b)` is `a.elements ∪ b.elements`. Set union is idempotent (re-adding something doesn't change the result), commutative, and associative — the three laws that guarantee convergence.

## Code examples

**Add and merge:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GSetTest.kt#addProducesADeltaThatAddsAnElement / mergeIsUnion -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GSetTest.kt
// Tests: addProducesADeltaThatAddsAnElement / mergeIsUnion
val empty = GSet.empty<String>()
val delta = empty.add("x")
val next = empty.piece(delta)
assertTrue(next.contains("x"))

val left = GSet.of("x", "y")
val right = GSet.of("y", "z")
assertEquals(setOf("x", "y", "z"), left.piece(right).elements)
```

## When to use

`GSet` is the right choice when elements are only ever added — completed tasks, acknowledged events, registered participants. For a set where elements can be removed, see [ORSet](crdt-orset.md) (observe-remove, add-wins on conflict) or [TwoPhaseSet](crdt-twophaseset.md) (remove-wins, permanent tombstone).
