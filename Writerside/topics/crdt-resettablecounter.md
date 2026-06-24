# ResettableCounter

A counter you can reset to zero at any time — without coordination, and without losing increments that happened at the same moment on other devices.

**Converges to:** a value that reflects every increment not yet observed by any reset, from any device, regardless of network order or duplication.

## Why not just set the counter back to zero?

If two devices both have a counter at 10 and one of them resets, the other device doesn't know yet. When they sync, a naïve "take the smaller value" rule would see 10 and 0 and might pick 10 — ignoring the reset entirely. Or it might always take 0 — losing the increments the other device added *after* the reset.

`ResettableCounter` does neither. It uses a causal timestamp on each increment so a reset can say precisely: "I'm clearing the increments I have seen; anything I haven't seen yet is fine."

## The key property

**An increment concurrent with a reset survives.** A reset removes only the increments it causally observed. If device A increments while device B's reset is in flight (B hasn't delivered the reset to A yet), A's increment is concurrent — its causal timestamp postdates the reset — and it appears in the merged value.

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ResettableCounterTest.kt#incrementConcurrentWithResetSurvives -->
```kotlin
// Shared start: A has incremented 5
val start = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(a, 5L))

// B resets based on what it saw (the 5 from A)
val afterReset = start.piece(start.reset())

// Concurrently, A increments again (A hasn't seen B's reset yet)
val concurrentIncrement = start.increment(a, 3L)
val aWithConcurrentIncrement = start.piece(concurrentIncrement)

// Merge: B's reset removes the 5 it saw, but A's +3 (which B never saw) survives
val merged = afterReset.piece(aWithConcurrentIncrement)
assertEquals(3L, merged.value)
```

## Code examples

**Increment on behalf of a replica:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ResettableCounterTest.kt" include-symbol="incrementIncreasesValue" }

**Reset to zero (observed-reset: concurrent increments survive):**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ResettableCounterTest.kt" include-symbol="resetClearsToZero" }

**Multiple replicas converge:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/ResettableCounterTest.kt" include-symbol="multipleReplicaIncrementsSum" }

## How it works

Internally, `ResettableCounter` is a `Causal<DotFun<Long>>` — the same causal plumbing that powers `ORSet` and `MVRegister`. Each `increment(by)` call mints a fresh `(replica, seq)` dot that carries the `by` amount. A `reset()` moves every live dot into the causal context (tombstoning it) and empties the store.

When two replicas merge:
- Dots live in both stores survive.
- Dots live in one store but *already witnessed* by the other's context are dropped — they were explicitly retired (by a reset).
- Dots live in one store but *not yet witnessed* by the other's context survive — they are concurrent with whatever happened on the other side.

This is the standard causal-CRDT rule. `ResettableCounter` just applies it to a counter.

## Serialization note

The internal dot map uses `Dot` (a data class) as a map key. Standard JSON requires `Json { allowStructuredMapKeys = true }` to encode it. CBOR and Protobuf encode it cleanly without any flag.
