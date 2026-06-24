# Bloom Filter

An approximate membership check that uses far less memory than a real set. You can ask "have I seen this before?" and get a fast, compact answer — with a small, configurable chance of a false yes, but **never a false no**.

**Converges to:** the union of all elements ever added across all replicas.

## Merge rule

`piece(a, b)` is bitwise OR of the two bit arrays. OR is idempotent (re-adding something doesn't change the result), commutative, and associative — the three laws that guarantee convergence.

## Union-only — no removes

A Bloom filter is **grow-only**: once a bit is set, it stays set. Removing an element would require clearing bits that may have been set by *other* elements, breaking the monotone-join property that makes this a CRDT. This is by design, not an oversight.

> A Counting Bloom filter supports removes but loses idempotency (a bit decremented twice produces wrong state), so it does not satisfy the `Quilted` contract. If you need probabilistic removes, combine a `BloomFilter` with a `GSet` of tombstones.

## Configuring the filter

Two parameters control the accuracy / size trade-off:

- **`expectedElements`** — the number of distinct elements you expect to add. Undersizing increases false-positive rates; oversizing wastes memory.
- **`falsePositiveRate`** — the fraction of non-members that will incorrectly report as present (default 1%). A tighter rate needs more bits.

The optimal bit count and number of hash functions are derived automatically from these two values.

## Merge compatibility

Two `BloomFilter` instances can only be `piece`d if they were created with the same `bitCount` and `hashCount`. Use `BloomFilter.create` with the same `expectedElements` and `falsePositiveRate` on every replica.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleBloomFilter -->

```kotlin
// Both replicas share the same configuration: 1 000 expected elements, 1% FP rate.
var replicaA = BloomFilter.create(expectedElements = 1_000, falsePositiveRate = 0.01)
var replicaB = BloomFilter.create(expectedElements = 1_000, falsePositiveRate = 0.01)

// Each replica adds its own element independently.
replicaA = replicaA.piece(replicaA.add("alice"))
replicaB = replicaB.piece(replicaB.add("bob"))

// After merging (bitwise OR), both elements are visible to either replica.
val merged = replicaA.piece(replicaB)
check(merged.mightContain("alice"))  // no false negatives
check(merged.mightContain("bob"))    // no false negatives
```

## When to use

`BloomFilter` is the right choice when you need compact, network-efficient membership tracking and can tolerate rare false positives — for example, as a **set-reconciliation probe** in an anti-entropy path ("here's roughly what I have; skip sending elements the other side already knows about"). For exact membership, use [GSet](crdt-gset.md) or [ORSet](crdt-orset.md).
