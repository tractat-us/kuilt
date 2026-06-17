# ORMap

An observe-remove map: key presence is tracked by an ORSet (add-wins on concurrent remove/re-add), and values merge via their own `Quilted` CRDT. The key set and the value for each key are independent lattices.

**Converges to:** a map where key presence follows ORSet semantics (add-wins on conflict) and each value converges according to its own `piece` rule.

## Merge rule

Key presence is an ORSet of presence dots. Value merging is the value type's own `piece`. When a key is removed by one replica and re-added with a new value by another concurrently, the ORSet semantics apply: the new add's dot survives, so the key is present, and the value is the merge of both sides.

## Code examples

**Put and query:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt" include-symbol="putThenContains" }

**Values merge via their own `piece`:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt" include-symbol="valuesMergeViaTheirOwnPiece" }

**Add wins over concurrent remove:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt" include-symbol="addWinsOverConcurrentRemove" }

## When to use

`ORMap` is the general-purpose CRDT map. It is a good fit when both key lifetime and value merging matter — for example, a map from player id to `GCounter` vote tally, where players can join and leave concurrently. For a simpler map where values are plain scalars and you want last-write-wins on each key, use [LWWMap](crdt-lwwmap.md).
