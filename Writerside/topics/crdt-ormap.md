# ORMap

A map where keys can be added and removed, and values can be any CRDT. When two devices edit at the same time — one removes a key, another writes to it — the key survives (add-wins). The value at that key merges normally using its own type's rules.

**Converges to:** a map where key presence follows ORSet semantics (add-wins on conflict) and each value converges according to its own `piece` rule.

## Merge rule

Key presence is an ORSet of presence dots. Value merging is the value type's own `piece`. When a key is removed by one replica and re-added with a new value by another concurrently, the ORSet semantics apply: the new add's dot survives, so the key is present, and the value is the merge of both sides.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORMap -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val start = ORMap.empty<String, GSet<String>>()
    .put(a, "team", GSet.of("alice"))

val alice = start.remove("team")                          // Alice removes the key
val bob = start.put(b, "team", GSet.of("bob"))            // Bob concurrently adds

val merged = alice.piece(bob)
check("team" in merged.keys)                               // add-wins on the key
```

## When to use

`ORMap` is the general-purpose CRDT map. It is a good fit when both key lifetime and value merging matter — for example, a map from player id to `GCounter` vote tally, where players can join and leave concurrently. For a simpler map where values are plain scalars and you want last-write-wins on each key, use [LWWMap](crdt-lwwmap.md).
