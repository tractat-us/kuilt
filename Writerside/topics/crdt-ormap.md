# ORMap

An observe-remove map: key presence is tracked by an ORSet (add-wins on concurrent remove/re-add), and values merge via their own `Quilted` CRDT. The key set and the value for each key are independent lattices.

**Converges to:** a map where key presence follows ORSet semantics (add-wins on conflict) and each value converges according to its own `piece` rule.

## Merge rule

Key presence is an ORSet of presence dots. Value merging is the value type's own `piece`. When a key is removed by one replica and re-added with a new value by another concurrently, the ORSet semantics apply: the new add's dot survives, so the key is present, and the value is the merge of both sides.

## Code examples

**Put and query:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt#putThenContains -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt
// Test: putThenContains
val m = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
assertTrue("votes" in m.keys)
assertEquals(1L, m["votes"]?.value)
```

**Values merge via their own `piece`:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt#valuesMergeViaTheirOwnPiece -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt
// Test: valuesMergeViaTheirOwnPiece
val mA = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 3L))
val mB = ORMap.empty<String, GCounter>().put(b, "votes", GCounter.of(b to 5L))
val merged = mA.piece(mB)
assertEquals(8L, merged["votes"]?.value)
```

**Add wins over concurrent remove:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt#addWinsOverConcurrentRemove -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ORMapTest.kt
// Test: addWinsOverConcurrentRemove
val start = ORMap.empty<String, GCounter>().put(a, "votes", GCounter.of(a to 1L))
val alice = start.remove("votes")
val bob = start.put(b, "votes", GCounter.of(b to 1L))
val merged = alice.piece(bob)
assertTrue("votes" in merged.keys)  // add wins
assertEquals(2L, merged["votes"]?.value)
```

## When to use

`ORMap` is the general-purpose CRDT map. It is a good fit when both key lifetime and value merging matter — for example, a map from player id to `GCounter` vote tally, where players can join and leave concurrently. For a simpler map where values are plain scalars and you want last-write-wins on each key, use [LWWMap](crdt-lwwmap.md).
