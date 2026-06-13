# GCounter

A grow-only counter. The value can only increase. Each replica owns its own slot; the total is the sum of all slots. Merge is element-wise maximum.

**Converges to:** the total number of increments applied across all replicas, with no replica able to affect another's slot.

## Merge rule

Each replica `r` owns slot `r`. `inc(r, n)` raises slot `r` by `n`. `piece(a, b)` takes element-wise max of every slot:

```
piece({A:2, B:1}, {A:1, B:3}) = {A:2, B:3}   // max per slot, not sum
```

This is why merging doesn't double-count: the same increment, seen from two replicas, merges idempotently.

## Code examples

**Zero counter and summing across replicas:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt#zeroHasValueZero -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt
// Test: zeroHasValueZero / valueSumsAcrossReplicas
assertEquals(0L, GCounter.ZERO.value)
assertEquals(7L, GCounter.of(a to 2L, b to 5L).value)
```

**Inc produces a delta:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt#incProducesADeltaThatRaisesTheCount -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt
// Test: incProducesADeltaThatRaisesTheCount
val gc = GCounter.ZERO
val delta = gc.inc(a, 3L)
val next = gc.piece(delta)
assertEquals(3L, next.value)
assertEquals(3L, next.count(a))
```

**Merge is element-wise max, not sum:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt#pieceTakesElementwiseMaxNotSum -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt
// Test: pieceTakesElementwiseMaxNotSum
assertEquals(
    GCounter.of(a to 2L, b to 3L),
    GCounter.of(a to 2L, b to 1L).piece(GCounter.of(a to 1L, b to 3L)),
)
```

**JSON round-trip:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt#roundTripsThroughJson -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt
// Test: roundTripsThroughJson
val gc = GCounter.of(a to 2L, b to 5L)
val encoded = Json.encodeToString(GCounter.serializer(), gc)
assertEquals(gc, Json.decodeFromString(GCounter.serializer(), encoded))
```

## When to use

Use `GCounter` when you only need to count up — events fired, messages sent, operations completed. For a counter that can also go down, see [PNCounter](crdt-pncounter.md). For a counter with a shared budget that must never be overdrawn, see [BoundedCounter](crdt-bounded-counter.md).
