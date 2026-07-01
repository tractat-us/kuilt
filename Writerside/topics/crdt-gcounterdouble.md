# GCounterDouble

A running total, with a fractional part, that only ever goes up. Several devices can each add to it on their own — no coordination, even offline — and when they sync the totals always agree. It is the decimal twin of [GCounter](crdt-gcounter.md): use it when the thing you are counting isn't whole numbers, like seconds of processing time or megabytes transferred.

**Converges to:** the sum of every device's own running total, with no device able to affect another's slot.

## Merge rule

Each replica `r` owns slot `r`. `inc(r, n)` raises slot `r` by `n` (which must be positive — this counter only grows). `piece(a, b)` takes the element-wise maximum of every slot, exactly like `GCounter`, so the same increment seen from two devices is never double-counted.

## The one honest limit: adding up decimals

There is one subtlety a whole-number counter doesn't have. Adding decimals in a different order can give a very slightly different answer — that's just how floating-point arithmetic works on every computer. The *stored* state always converges to the same thing; only the final total, read out by adding the slots together, could drift. `GCounterDouble` avoids this by always summing the slots in the same canonical order (sorted by replica id), so every device computes the identical total.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGCounterDouble -->
```kotlin
val phone = ReplicaId("phone")
val watch = ReplicaId("watch")

// Each device independently accumulates fractional seconds of CPU time.
var onPhone = GCounterDouble.ZERO
onPhone = onPhone.piece(onPhone.inc(phone, 0.75).delta)

var onWatch = GCounterDouble.ZERO
onWatch = onWatch.piece(onWatch.inc(watch, 0.5).delta)

// Merge either direction — the total is the same, to the bit.
val total = onPhone.piece(onWatch).value // 1.25
check(total == 1.25)
```
