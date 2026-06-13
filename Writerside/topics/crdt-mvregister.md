# MVRegister

A multi-value register: holds potentially multiple values when writes happen concurrently. A subsequent write that observes all concurrent values resolves them.

**Converges to:** the set of values written concurrently at the causal frontier — a single value when writes are sequential, multiple values when they are truly concurrent.

## Merge rule

`MVRegister` uses a `DotFun` (a map from causal dots to values). Each `set(replica, value)` mints a new dot, superseding all dots the replica has already seen. On merge, a dot that was witnessed-and-superseded by one side is removed; a dot unknown to the other side is kept. The result is the set of values at the causal frontier.

## Code examples

**Set and read:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/MVRegisterTest.kt#setThenRead -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/MVRegisterTest.kt
// Test: setThenRead
assertEquals(setOf("x"), MVRegister.empty<String>().set(a, "x").values)
```

**Concurrent writes keep both values:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/MVRegisterTest.kt#concurrentWritesKeepBothValues -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/MVRegisterTest.kt
// Test: concurrentWritesKeepBothValues
val base = MVRegister.empty<String>()
val x = base.set(a, "x")
val y = base.set(b, "y")
assertEquals(setOf("x", "y"), x.piece(y).values)
```

**A later write resolves the conflict:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/MVRegisterTest.kt#aLaterWriteResolvesTheConflict -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/MVRegisterTest.kt
// Test: aLaterWriteResolvesTheConflict
val base = MVRegister.empty<String>()
val conflicted = base.set(a, "x").piece(base.set(b, "y")) // {x, y}
val resolved = conflicted.set(a, "z")                     // observes both, supersedes
assertEquals(setOf("z"), resolved.values)
```

## When to use

`MVRegister` is appropriate when concurrent writes are possible and you want to surface the conflict explicitly rather than silently dropping a value. The application can inspect `values.size > 1` to detect and present a conflict. For silent last-write-wins, use [LWWRegister](crdt-lwwregister.md).
