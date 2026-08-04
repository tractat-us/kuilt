# ORMap

A map where keys can be added and removed, and values can be any CRDT. When two devices edit at the same time — one removes a key, another writes to it — the key survives (add-wins), holding what the writer wrote. Writes that nobody removed are kept and combined using the value's own rules.

**Converges to:** a map where key presence follows ORSet semantics (add-wins on conflict) and each key's value is every surviving write to it, combined by that value type's own `piece` rule.

## Merge rule

Key presence is an ORSet of presence dots, and **each dot carries the write that was made under it**. A key's value is the join of the writes whose dots are still live, so a removal takes away the writes sitting on the dots it retired.

That is what makes a delivery order irrelevant. When one replica removes a key and another concurrently writes to it, the writer's dot survives — the remover never saw it — so the key is present with the writer's value, and the removed write is gone whichever order the two states are merged in. Keeping one value beside the tags instead would make the answer depend on that order, which is [issue 2086](https://github.com/tractat-us/kuilt/issues/2086).

One consequence worth knowing before you rely on removal to erase something. Writing to a key again *moves* that replica's earlier writes onto the new dot, so they are no longer sitting where a concurrent remover can reach them: if A writes, B sees it, and then A writes again while B removes the key, A's first write survives. It rode a dot B never saw. Removal erases what it observed at the dot it observed it on — it is not a guarantee that a value is gone everywhere for good.

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
