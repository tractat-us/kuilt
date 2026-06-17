# PNCounter

A positive/negative counter: the simplest extension of `GCounter` that allows both increments and decrements.

**Converges to:** `inc.value - dec.value`, where each half is a `GCounter` that only ever grows.

## Worked example — live vote tally over two peers

`PNCounter` + `SeamReplicator` is the natural fit for a vote tally: each peer owns its own slot, increments record upvotes, decrements record downvotes. Deltas propagate automatically; both replicas converge to the same net count.

<!-- verbatim from examples/src/test/kotlin/us/tractat/kuilt/examples/VoteTallyTest.kt#upvotes and downvotes from two peers converge to the correct net tally -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/examples/src/test/kotlin/us/tractat/kuilt/examples/VoteTallyTest.kt
// Test: upvotes and downvotes from two peers converge to the correct net tally
val loom = InMemoryLoom()
val seamAlice = loom.host(Pattern("vote-tally"))
val seamBob = loom.join(InMemoryTag("bob"))

val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)
val aliceTally = SeamReplicator(seamAlice, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = replicatorCfg)
val bobTally = SeamReplicator(seamBob, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = replicatorCfg)

delay(1) // let collectors subscribe under StandardTestDispatcher

// Alice casts 3 upvotes for the post.
aliceTally.mutate { it.increment(aliceTally.replica, 3L) }

// Bob casts 1 upvote and then 1 downvote (changed his mind).
bobTally.mutate { it.increment(bobTally.replica, 1L) }
bobTally.mutate { it.decrement(bobTally.replica, 1L) }

// Alice adds another upvote concurrently.
aliceTally.mutate { it.increment(aliceTally.replica, 2L) }

delay(10) // advance virtual time so all delta broadcasts deliver

// Both replicas must converge to the same net tally.
// alice: +3 +2 = 5 increments; bob: +1 -1 = 0 net → total = 5
assertEquals(5L, aliceTally.state.value.value)
assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
```

See the full test at [`VoteTallyTest.kt`](https://github.com/tractat-us/kuilt/blob/main/examples/src/test/kotlin/us/tractat/kuilt/examples/VoteTallyTest.kt).

## Merge rule

A `PNCounter` is two independent `GCounter`s in a product lattice — one for increments (`inc`), one for decrements (`dec`). Joining two `PNCounter`s joins each half separately. Idempotent, commutative, associative by the same argument that holds for `GCounter`.

```
value = inc.value - dec.value
```

There is no floor at zero. A replica can decrement without having incremented — `value` can go negative.

## Code examples

**Increment:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt#incrementRaisesValue -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt
// Test: incrementRaisesValue
val pn = PNCounter.ZERO
val next = pn.piece(pn.increment(a, 3L))
assertEquals(3L, next.value)
```

**Decrement:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt#decrementLowersValue -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt
// Test: decrementLowersValue
val pn = PNCounter.ZERO.piece(PNCounter.ZERO.increment(a, 5L))
val next = pn.piece(pn.decrement(a, 2L))
assertEquals(3L, next.value)
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
