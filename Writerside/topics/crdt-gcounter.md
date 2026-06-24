# GCounter

A counter that only goes up. Each device owns its own slot; the total is the sum across all devices. On merge each slot keeps whichever value is larger — so the same increment seen from two devices is never double-counted.

**Converges to:** the total number of increments applied across all replicas, with no replica able to affect another's slot.

## Merge rule

Each replica `r` owns slot `r`. `inc(r, n)` raises slot `r` by `n`. `piece(a, b)` takes element-wise max of every slot:

```
piece({A:2, B:1}, {A:1, B:3}) = {A:2, B:3}   // max per slot, not sum
```

This is why merging doesn't double-count: the same increment, seen from two replicas, merges idempotently.

## Code examples

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGCounter -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

var replicaA = GCounter.ZERO
var replicaB = GCounter.ZERO

// Each replica increments its own slot.
replicaA = replicaA.piece(replicaA.inc(a, 3))
replicaB = replicaB.piece(replicaB.inc(b, 5))

// After merging both deltas, every replica converges to the same value.
val merged = replicaA.piece(replicaB)
check(merged.value == 8L) // 3 + 5
```

**Piece is element-wise max — the same increment is never double-counted:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGCounterPiece -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val left = GCounter.of(a to 2L, b to 1L)
val right = GCounter.of(a to 1L, b to 3L)
// merge takes max per slot: a→2, b→3
check(left.piece(right) == GCounter.of(a to 2L, b to 3L))
```

## When to use

Use `GCounter` when you only need to count up — events fired, messages sent, operations completed. For a counter that can also go down, see [PNCounter](crdt-pncounter.md). For a counter with a shared budget that must never be overdrawn, see [BoundedCounter](crdt-bounded-counter.md).
