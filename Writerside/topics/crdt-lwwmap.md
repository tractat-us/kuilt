# LWWMap

A map where each key is an `LWWRegister`. Concurrent writes to the same key are resolved by timestamp — the later write wins. Keys are independent, so a conflict on one key never affects another.

**Converges to:** a map where each key holds the value written at the highest `(timestamp, replicaId)` for that key — or no value, if the latest write was a `remove`.

## Merge rule

`LWWMap` is a map from key to `LWWRegister<V>`. `piece` merges each key's register independently using the LWW rule. Keys with no conflict simply union.

`remove` is just another timestamped write — a *tombstone* that hides the key from reads. A remove at a later timestamp deletes a concurrently-set value; a set at a later timestamp revives the key. Ties break on `replicaId`, same as any other write.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWMap -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val left = LWWMap.empty<String, Int>()
    .set(a, timestamp = 1L, key = "score", value = 10)
val right = LWWMap.empty<String, Int>()
    .set(b, timestamp = 2L, key = "score", value = 20)

val merged = left.piece(right)
check(merged["score"] == 20)  // ts=2 wins for this key

// remove is a write like any other: a later remove hides the key…
val removed = merged.remove(a, timestamp = 3L, key = "score")
check(removed["score"] == null)

// …and a concurrent later set beats a concurrent earlier remove.
val rewritten = merged.set(b, timestamp = 4L, key = "score", value = 30)
check(removed.piece(rewritten)["score"] == 30)
```

## When to use

`LWWMap` is a good fit for converging metadata — display names, preferences, labels — where per-key last-write-wins semantics are acceptable. For a map whose keys are ORSet-managed (add-wins on key presence) and whose values merge via their own CRDT, use [ORMap](crdt-ormap.md).
