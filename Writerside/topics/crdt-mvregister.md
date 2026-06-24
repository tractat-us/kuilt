# MVRegister

Holds one value, but surfaces conflicts instead of silently picking a winner. When two devices write at the same time, both values are kept. The next write — one that has seen both — resolves them back to one.

**Converges to:** the set of values written concurrently at the causal frontier — a single value when writes are sequential, multiple values when they are truly concurrent.

## Merge rule

`MVRegister` uses a `DotFun` (a map from causal dots to values). Each `set(replica, value)` mints a new dot, superseding all dots the replica has already seen. On merge, a dot that was witnessed-and-superseded by one side is removed; a dot unknown to the other side is kept. The result is the set of values at the causal frontier.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleMVRegister -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Two replicas set independently — neither has seen the other.
val fromA = MVRegister.empty<String>().set(a, "vA")
val fromB = MVRegister.empty<String>().set(b, "vB")

val merged = fromA.piece(fromB)
check(merged.values == setOf("vA", "vB"))  // concurrent writes retained

// A later write on one replica that observes the merged state resolves it.
val resolved = merged.set(a, "resolved")
check(resolved.values == setOf("resolved"))
```

## When to use

`MVRegister` is appropriate when concurrent writes are possible and you want to surface the conflict explicitly rather than silently dropping a value. The application can inspect `values.size > 1` to detect and present a conflict. For silent last-write-wins, use [LWWRegister](crdt-lwwregister.md).
