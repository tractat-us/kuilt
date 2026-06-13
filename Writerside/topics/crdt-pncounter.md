# PNCounter

A positive/negative counter: the simplest extension of `GCounter` that allows both increments and decrements.

**Converges to:** `inc.value - dec.value`, where each half is a `GCounter` that only ever grows.

## Merge rule

A `PNCounter` is two independent `GCounter`s in a product lattice — one for increments (`inc`), one for decrements (`dec`). Joining two `PNCounter`s joins each half separately. Idempotent, commutative, associative by the same argument that holds for `GCounter`.

```
value = inc.value - dec.value
```

There is no floor at zero. A replica can decrement without having incremented — `value` can go negative.

## Code examples

**Increment and decrement:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt#incrementRaisesValue / decrementLowersValue -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt
// Tests: incrementRaisesValue / decrementLowersValue
val pn = PNCounter.ZERO
val afterInc = pn.piece(pn.increment(a, 3L))
assertEquals(3L, afterInc.value)

val afterDec = afterInc.piece(afterInc.decrement(a, 2L))
assertEquals(1L, afterDec.value)
```

**Concurrent increments from different replicas merge:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt#concurrentIncAndDecFromDifferentReplicasMerge -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt
// Test: concurrentIncAndDecFromDifferentReplicasMerge
val zero = PNCounter.ZERO
val aInc = zero.piece(zero.increment(a, 10L))
val bDec = zero.piece(zero.decrement(b, 3L))
val merged = aInc.piece(bDec)
assertEquals(7L, merged.value)
```

**Value can go negative:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt#valueCanGoNegative -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt
// Test: valueCanGoNegative
val pn = PNCounter.ZERO.piece(PNCounter.ZERO.decrement(a, 5L))
assertEquals(-5L, pn.value)
```

## When to use

| Need | Use |
|------|-----|
| Concurrent add/remove of an integer, any sign | `PNCounter` |
| Shared budget that must never go negative | `BoundedCounter` |
| Only ever counts up | `GCounter` |
