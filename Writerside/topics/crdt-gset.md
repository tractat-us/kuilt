# GSet

A set that only grows. Elements can be added but never removed. Merging two `GSet`s is just set union.

**Converges to:** the union of all elements ever added across all replicas.

## Merge rule

`piece(a, b)` is `a.elements ∪ b.elements`. Set union is idempotent (re-adding something doesn't change the result), commutative, and associative — the three laws that guarantee convergence.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```

## When to use

`GSet` is the right choice when elements are only ever added — completed tasks, acknowledged events, registered participants. For a set where elements can be removed, see [ORSet](crdt-orset.md) (observe-remove, add-wins on conflict) or [TwoPhaseSet](crdt-twophaseset.md) (remove-wins, permanent tombstone).
