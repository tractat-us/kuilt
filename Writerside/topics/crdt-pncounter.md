# PNCounter

A counter that can go up or down. Two `GCounter`s under the hood — one for increments, one for decrements. The current value is the difference.

**Converges to:** `inc.value - dec.value`, where each half is a `GCounter` that only ever grows.

## Worked example — live vote tally over two peers

`PNCounter` + `Quilter` is the natural fit for a vote tally: each peer owns its own slot, increments record upvotes, decrements record downvotes. Deltas propagate automatically; both replicas converge to the same net count.

See `QuilterSamples.sampleVoteTally` and the full integration test at [`VoteTallyTest.kt`](https://github.com/tractat-us/kuilt/blob/main/examples/src/test/kotlin/us/tractat/kuilt/examples/VoteTallyTest.kt).

## Merge rule

A `PNCounter` is two independent `GCounter`s in a product lattice — one for increments (`inc`), one for decrements (`dec`). Joining two `PNCounter`s joins each half separately. Idempotent, commutative, associative by the same argument that holds for `GCounter`.

```
value = inc.value - dec.value
```

There is no floor at zero. A replica can decrement without having incremented — `value` can go negative.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#samplePNCounter -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

var counter = PNCounter.ZERO
counter = counter.piece(counter.increment(a, 10))
counter = counter.piece(counter.decrement(b, 3))

check(counter.value == 7L)
```

## When to use

| Need | Use |
|------|-----|
| Concurrent add/remove of an integer, any sign | `PNCounter` |
| Shared budget that must never go negative | `BoundedCounter` |
| Only ever counts up | `GCounter` |
