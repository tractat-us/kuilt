# LWWMap

A map where each key is an `LWWRegister`. Concurrent writes to the same key are resolved by timestamp — the later write wins. Keys are independent, so a conflict on one key never affects another.

**Converges to:** a map where each key holds the value written at the highest `(timestamp, replicaId)` for that key — or no value, if the latest write was a `remove`.

## Merge rule

`LWWMap` is a map from key to `LWWRegister<V>`. `piece` merges each key's register independently using the LWW rule. Keys with no conflict simply union.

`remove` is just another timestamped write — a *tombstone* that hides the key from reads. A remove at a later timestamp deletes a concurrently-set value; a set at a later timestamp revives the key. Ties break on `replicaId`, same as any other write.

## What each change costs to send

Changing one setting should cost about as much as one setting. So `set` and `remove` hand you back
**the change** — the single cell they wrote — and that is what goes to the other devices. The size
of what travels is the same whether the map holds three keys or ten thousand.

In code, both mutators return a `Patch<LWWMap<K, V>>`. Hand it to a replicator with
`quilter.mutate { it.set(replica, timestamp, key, value) }`; to hold the resulting whole map outside
a replicator, absorb the patch: `map.piece { it.set(replica, timestamp, key, value) }`.

A removal's delta is a one-cell **tombstone** map, never an empty one. An empty map is the identity
of the merge: joining it says nothing at all, and the removal would never leave the device that made
it.

The one-cell delta is exact while the write's `(timestamp, replicaId)` tag beats the key's current
one — which is what a monotonic clock per replica already gives you, and what this type's
tag-uniqueness rule already requires. Outside that domain there is no delta to be had, because a
delta is *joined* and a join can only move the value forward: a write with a losing tag is dropped
immediately rather than showing locally until the next merge takes it away. Either way every replica
lands on the same value; see [issue 2087](https://github.com/tractat-us/kuilt/issues/2087).

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWMap -->
```kotlin
// Two peers have converged on a settings map.
var alpha = LWWMap.empty<String, String>()
    .piece { it.set(a, timestamp = 1L, key = "lang", value = "en") }
    .piece { it.set(a, timestamp = 2L, key = "tz", value = "UTC") }
    .piece { it.set(a, timestamp = 3L, key = "theme", value = "dark") }
var bravo = alpha

// B changes one setting and puts only that cell on the wire. The frame is the same size
// whether the map holds three keys or ten thousand, and the other keys are untouched.
val change = alpha.set(b, timestamp = 4L, key = "theme", value = "light")
alpha = alpha.piece(change)
bravo = bravo.piece(change)
check(alpha == bravo)
check(alpha["theme"] == "light")
check(alpha["lang"] == "en")
```

## When to use

`LWWMap` is a good fit for converging metadata — display names, preferences, labels — where per-key last-write-wins semantics are acceptable. For a map whose keys are ORSet-managed (add-wins on key presence) and whose values merge via their own CRDT, use [ORMap](crdt-ormap.md).
