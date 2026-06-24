# TwoPhaseSet

A set where removal is final. Elements can be added and removed, but once removed they can never come back. When an add and a remove happen at the same time, **remove wins**.

**Converges to:** the set of elements that have been added and not yet tombstoned, where tombstones are permanent.

## Merge rule

A `TwoPhaseSet` maintains two `GSet`s: an add-set `A` and a tombstone-set `R`. An element is present if `element ∈ A ∧ element ∉ R`. Merge is union of both sets independently. Because tombstones only grow, a tombstoned element remains absent in all future states — even if another replica concurrently adds it.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleTwoPhaseSet -->
```kotlin
var s = TwoPhaseSet.empty<String>()
s = s.piece(s.add("alice"))
check(s.contains("alice"))

s = s.piece(s.remove("alice"))
check(!s.contains("alice"))

// Even re-adding won't bring it back — the tombstone wins.
s = s.piece(s.add("alice"))
check(!s.contains("alice"))
```

## When to use

`TwoPhaseSet` is appropriate when removal semantics must be final — expired tokens, retired identifiers, blacklisted entries. For a set where removed elements can be re-added, use [ORSet](crdt-orset.md).
