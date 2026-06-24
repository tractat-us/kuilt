# ResettableCounter

Imagine a shared score or like-count that several people update while offline. Everyone's changes accumulate normally — and anyone can reset the counter to zero at any point, without asking the others. When the devices sync, the count reconciles automatically. Increments that happened at the exact same moment as a reset are not silently lost; they survive.

That is what `ResettableCounter` is for.

**Converges to:** the sum of every increment that was not yet observed by any reset, from every device, regardless of arrival order or duplication.

## The problem with a naïve reset

Suppose two devices both show a count of 10 and device B resets to zero while they are offline. When they sync later, a simple rule like "take the minimum" would ignore the reset and land back at 10. A rule like "always trust a reset" would silently erase any increment device A added concurrently — before B's reset reached it.

Neither is right. A reset should be precise: *clear what I have seen; leave what I haven't*.

## Why concurrent increments survive

A reset does not say "set the counter to zero". It says: "I am retiring every increment I am aware of right now." Each increment in `ResettableCounter` carries a causal timestamp — a dot `(replica, sequence)` that uniquely identifies that exact increment. When a reset fires, it moves every live dot it can see into a causal context (a tombstone record) and empties the store.

When two replicas merge, the causal-CRDT rule applies to every dot:

- A dot live on both sides stays.
- A dot live on one side but already in the other side's context is dropped — the other side explicitly retired it.
- A dot live on one side but *not yet in the other side's context* survives — it is concurrent with whatever happened on the other side.

A concurrent increment mints a dot the resetter had never seen. Its dot is not in the reset's context, so it passes through the merge unharmed.

## Example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleResettableCounter -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Shared start: A has incremented 10.
var shared = ResettableCounter.ZERO
shared = shared.piece(shared.increment(a, 10L))

// B resets based on what it observed (the 10 from A).
val afterReset = shared.piece(shared.reset())

// Concurrently, A increments 3 more — A hasn't seen B's reset yet.
val concurrentAdd = shared.piece(shared.increment(a, 3L))

// Merge: the pre-reset 10 is gone; the concurrent 3 survives.
val merged = afterReset.piece(concurrentAdd)
check(merged.value == 3L) // only the concurrent increment survived
```

## Internals

`ResettableCounter` is a thin wrapper around `Causal<DotFun<Long>>` — the same causal plumbing that powers `ORSet` and `MVRegister`. Internally:

- `increment(replica, by)` mints a fresh dot `(replica, nextSeq)` carrying the `by` amount, adds it to the store, and advances the causal context.
- `reset()` folds every live dot into the causal context and clears the store. Future merges will see those dots as already-retired.
- `piece(other)` is the causal-CRDT merge: keep dots live on both sides; drop dots that one side has in its context; keep dots the other side hasn't witnessed yet.

The `value` property is simply the sum of all amounts in the live dot store.

`increment` and `reset` follow the delta-state pattern: they return a `Patch` you absorb with `piece`. The counter itself is never mutated.

## Guarantee and honest constraint

**The increment-concurrent-with-reset guarantee is exact.** "Concurrent" has a precise causal meaning: the increment was minted on a replica that had not yet received the reset. If the increment was sent *after* the reset arrived, it is not concurrent — the next reset will retire it.

One practical constraint: increments must be positive (`by >= 1`). `ResettableCounter` counts up (and resets to zero); for a counter that also decrements, use [PNCounter](crdt-pncounter.md).

## When to use

| Situation | Use |
|-----------|-----|
| Score, tally, or count that anyone can clear to start fresh | `ResettableCounter` |
| Counter that also needs to go down | [PNCounter](crdt-pncounter.md) |
| Counter that must never go below zero | [BoundedCounter](crdt-bounded-counter.md) |
| Counter that only ever grows | [GCounter](crdt-gcounter.md) |

## Serialization note

The internal dot map uses `Dot` (a data class) as a map key. Standard JSON requires `Json { allowStructuredMapKeys = true }` to encode it. CBOR and Protobuf encode it without any flag.
