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

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt" include-symbol="zeroHasValueZero" }

**Inc produces a delta:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt" include-symbol="incProducesADeltaThatRaisesTheCount" }

**Merge is element-wise max, not sum:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt" include-symbol="pieceTakesElementwiseMaxNotSum" }

**JSON round-trip:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterTest.kt" include-symbol="roundTripsThroughJson" }

## When to use

Use `GCounter` when you only need to count up — events fired, messages sent, operations completed. For a counter that can also go down, see [PNCounter](crdt-pncounter.md). For a counter with a shared budget that must never be overdrawn, see [BoundedCounter](crdt-bounded-counter.md).
