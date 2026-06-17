# TwoPhaseSet

A two-phase set: elements can be added once and removed once. Removal is permanent — once an element is tombstoned, it can never be re-added. **Remove wins** over a concurrent re-add.

**Converges to:** the set of elements that have been added and not yet tombstoned, where tombstones are permanent.

## Merge rule

A `TwoPhaseSet` maintains two `GSet`s: an add-set `A` and a tombstone-set `R`. An element is present if `element ∈ A ∧ element ∉ R`. Merge is union of both sets independently. Because tombstones only grow, a tombstoned element remains absent in all future states — even if another replica concurrently adds it.

## Code examples

**Add and remove:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt" include-symbol="addThenContains" }

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt" include-symbol="removeMakesAbsent" }

**Remove wins over concurrent re-add:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt" include-symbol="removeWinsOverConcurrentReAdd" }

**Tombstone is permanent — resurrection is impossible:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/TwoPhaseSetTest.kt" include-symbol="cannotResurrectARemovedElement" }

## When to use

`TwoPhaseSet` is appropriate when removal semantics must be final — expired tokens, retired identifiers, blacklisted entries. For a set where removed elements can be re-added, use [ORSet](crdt-orset.md).
