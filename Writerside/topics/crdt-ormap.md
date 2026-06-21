# ORMap

A map where keys can be added and removed, and values can be any CRDT. When two devices edit at the same time — one removes a key, another writes to it — the key survives (add-wins). The value at that key merges normally using its own type's rules.

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
