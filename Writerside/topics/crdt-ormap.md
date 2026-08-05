# ORMap

A map where keys can be added and removed, and values can be any CRDT. When two devices edit at the same time — one removes a key, another writes to it — the key survives (add-wins), holding what the writer wrote. Writes that nobody removed are kept and combined using the value's own rules.

**Converges to:** a map where key presence follows ORSet semantics (add-wins on conflict) and each key's value is every surviving write to it, combined by that value type's own `piece` rule.

## Merge rule

Key presence is an ORSet of presence dots, and **each dot carries the write that was made under it**. A key's value is the join of the writes whose dots are still live, so a removal takes away the writes sitting on the dots it retired.

That is what makes a delivery order irrelevant. When one replica removes a key and another concurrently writes to it, the writer's dot survives — the remover never saw it — so the key is present with the writer's value, and the removed write is gone whichever order the two states are merged in. Keeping one value beside the tags instead would make the answer depend on that order, which is [issue 2086](https://github.com/tractat-us/kuilt/issues/2086).

One consequence worth knowing before you rely on removal to erase something. Writing to a key again *moves* that replica's earlier writes onto the new dot, so they are no longer sitting where a concurrent remover can reach them: if A writes, B sees it, and then A writes again while B removes the key, A's first write survives. It rode a dot B never saw. Removal erases what it observed at the dot it observed it on — it is not a guarantee that a value is gone everywhere for good.

## What each change costs to send

Adding one name to one team should cost about as much as one name. So `put` and `remove` hand you
back **the change** — the one key you touched — and that is what goes to the other devices. The size
of what travels does not depend on how many keys the map holds, nor on how much everybody else has
already written under that key.

In code, both mutators return a `Patch<ORMap<K, S>>`. Hand it to a replicator with
`quilter.mutate { it.put(replica, key, value) }`; to hold the resulting whole map outside a
replicator, absorb the patch: `map.piece { it.put(replica, key, value) }`.

A put's delta carries **the value you passed**, not the merged result of your value and what was
already stored. The receiver re-does that merge against its own copy, which is the copy that
matters there — so on a nested `ORMap<String, ORSet<String>>` a device adding one member ships one
name, not the roster. What the delta cannot leave out is the sender's own earlier writes to that
key, because the tag this put mints supersedes them and has to carry what they held; a replica
growing one key alone therefore pays for its own history each time
([issue 2102](https://github.com/tractat-us/kuilt/issues/2102)).

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORMap -->
```kotlin
// Two peers have converged: "team" already holds a long roster, put there by B.
var alpha = ORMap.empty<String, GSet<String>>()
    .piece { it.put(b, "team", GSet.of("alice", "bob", "carol", "dan")) }
var bravo = alpha

// A adds one member and puts only the change on the wire. The delta carries A's one name —
// not the merged roster — because the receiver re-does that merge against its own copy.
val hire = alpha.put(a, "team", GSet.of("erin"))
check(hire.delta["team"] == GSet.of("erin"))

// A's tag joins B's rather than replacing it, so the key's value is both writes together.
alpha = alpha.piece(hire)
bravo = bravo.piece(hire)
check(alpha == bravo)
check(alpha["team"] == GSet.of("alice", "bob", "carol", "dan", "erin"))
```

## When to use

`ORMap` is the general-purpose CRDT map. It is a good fit when both key lifetime and value merging matter — for example, a map from player id to `GCounter` vote tally, where players can join and leave concurrently. For a simpler map where values are plain scalars and you want last-write-wins on each key, use [LWWMap](crdt-lwwmap.md).
