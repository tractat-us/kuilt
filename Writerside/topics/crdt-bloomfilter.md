# Bloom Filter

Imagine you have a list of articles you've already read, synced across your phone and laptop — even when they're both offline. You don't need to know the exact list at any moment. You just need to answer one question: "have I seen this before?"

A Bloom filter is built for exactly that. It uses far less memory than a real set and answers the question almost instantly — with one honest caveat.

**Converges to:** the union of all elements ever added across all replicas.

## The one honest caveat — false positives

A Bloom filter can occasionally answer "yes, I've seen this" when you haven't. This is called a **false positive**. It will never, however, answer "no, I haven't seen this" when you have. That promise is absolute.

In everyday terms:

- "Yes, definitely seen this" → might occasionally be wrong
- "No, definitely haven't seen this" → always correct

For the "have I read this article?" use case, that trade-off is fine. If the filter says "read it", you might occasionally be shown a skipped article. If it says "not read", you will never miss one you already saw.

The false-positive rate is tunable — you choose how often wrong "yes" answers are acceptable when you create the filter.

## Union-only — you can't remove elements

Once you add something to a Bloom filter, it stays. You cannot remove it.

This isn't a missing feature; it's the reason merging works at all. Internally, adding an element sets several bits in a compact array. Different elements may share some of those bits. If you could clear bits on removal, you'd accidentally un-mark other elements that happened to set the same bits — breaking everything.

The result is a **grow-only** structure that merges cleanly across devices by taking the bitwise OR of both arrays. OR is the perfect join operation: it only sets bits, never clears them; applying it twice is the same as applying it once; the order doesn't matter; you can chain as many merges as you like.

> If you need approximate membership **with** removes, combine a `BloomFilter` with a `GSet` of tombstones. A Counting Bloom filter supports removes but loses the idempotency property that makes it a CRDT, so it doesn't fit the `Quilted` contract.

## How it works

Under the hood, a Bloom filter is a compact bit array paired with a small number of hash functions (typically 6–10 for a 1% false-positive rate). When you add an element:

1. The element's string is hashed into `k` bit positions using a double-hashing scheme: `hᵢ(x) = (h₁ + i·h₂) mod m`, for i from 0 to k−1.
2. Every one of those positions is set to 1 in the bit array.

When you ask `mightContain(element)`, the same hash positions are computed. If every one is set to 1, the element is probably present. If any one is 0, the element was definitely never added.

False positives arise because two different elements can overlap on all their bit positions by chance. The more elements you add relative to the array size, the more likely that becomes — which is why tuning `expectedElements` upfront matters.

**Merging** is bitwise OR across the two arrays — the positions set by replica A and the positions set by replica B are all preserved in the merged result. OR is idempotent, commutative, and associative: the three lattice laws that guarantee convergence.

The false-positive probability after adding `n` elements into `m` bits with `k` hashes is approximately:

```
p ≈ (1 − e^(−k·n/m))^k
```

`BloomFilter.create` uses the standard optimal sizing formulas to choose `m` and `k` for you:

```
m = −n · ln(p) / (ln 2)²
k = (m / n) · ln 2
```

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

## Configuring the filter

Two parameters control accuracy versus memory usage:

- **`expectedElements`** — how many distinct elements you expect to add. Undersizing increases the false-positive rate; oversizing wastes memory. Get it roughly right; precision doesn't matter much.
- **`falsePositiveRate`** — the fraction of non-members that will incorrectly report as present (default 1%). A tighter rate requires more bits. 0.1% uses roughly 50% more memory than 1%.

The optimal bit count and number of hash functions are derived automatically.

**Merge compatibility:** two `BloomFilter` instances can only be merged if they were created with the same `bitCount` and `hashCount`. Use `BloomFilter.create` with the same `expectedElements` and `falsePositiveRate` on every replica — or pass the raw parameters explicitly if you're building one from serialized state.

## Guarantees

| Property | Guarantee |
|---|---|
| False negatives | **None.** An element that was added always reports present. |
| False positives | At most `falsePositiveRate` fraction of non-members, given correct sizing. |
| Merge convergence | Bitwise OR — idempotent, commutative, associative. All replicas converge to the same set of bits. |
| Removes | **Not supported.** Once a bit is set it stays set. |
| Merge compatibility | Requires identical `bitCount` and `hashCount` across replicas. |
| Hash portability | Murmur3-inspired 64-bit double-hash — fully deterministic across all Kotlin targets. |

## When to use

`BloomFilter` is the right choice when you need compact, network-efficient membership tracking and can tolerate rare false positives — for example, as a **set-reconciliation probe** in an anti-entropy path ("here is roughly what I have; skip sending elements the other side already knows about"). It is also well-suited to "have I seen this event?" deduplication, "has this peer been announced?" checks, and any scenario where the set is write-heavy and reads tolerate the occasional false yes.

For exact membership, use [GSet](crdt-gset.md) or [ORSet](crdt-orset.md). For a set where you need removes, see [ORSet](crdt-orset.md) (add-wins) or [TwoPhaseSet](crdt-twophaseset.md) (remove-wins, permanent tombstone).
