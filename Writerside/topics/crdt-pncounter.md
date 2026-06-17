# PNCounter

A positive/negative counter: the simplest extension of `GCounter` that allows both increments and decrements.

**Converges to:** `inc.value - dec.value`, where each half is a `GCounter` that only ever grows.

## Worked example — live vote tally over two peers

`PNCounter` + `Quilter` is the natural fit for a vote tally: each peer owns its own slot, increments record upvotes, decrements record downvotes. Deltas propagate automatically; both replicas converge to the same net count.

```kotlin
```
{ src="../../kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt" include-symbol="sampleVoteTally" }

See the full test at [`VoteTallyTest.kt`](https://github.com/tractat-us/kuilt/blob/main/examples/src/test/kotlin/us/tractat/kuilt/examples/VoteTallyTest.kt).

## Merge rule

A `PNCounter` is two independent `GCounter`s in a product lattice — one for increments (`inc`), one for decrements (`dec`). Joining two `PNCounter`s joins each half separately. Idempotent, commutative, associative by the same argument that holds for `GCounter`.

```
value = inc.value - dec.value
```

There is no floor at zero. A replica can decrement without having incremented — `value` can go negative.

## Code examples

**Increment:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt" include-symbol="incrementRaisesValue" }

**Decrement:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt" include-symbol="decrementLowersValue" }

**Concurrent increments from different replicas merge:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt" include-symbol="concurrentIncAndDecFromDifferentReplicasMerge" }

**Value can go negative:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/PNCounterTest.kt" include-symbol="valueCanGoNegative" }

## When to use

| Need | Use |
|------|-----|
| Concurrent add/remove of an integer, any sign | `PNCounter` |
| Shared budget that must never go negative | `BoundedCounter` |
| Only ever counts up | `GCounter` |
