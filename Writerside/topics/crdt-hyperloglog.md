# HyperLogLog

Count distinct items across the network with a fixed, tiny memory footprint. Instead of keeping a full set of seen items, each peer maintains a small array of registers (~16 KB at default settings) and reports an approximate count that converges across all peers when merged.

**Converges to:** an estimate of the number of distinct items added across all replicas. The estimate is within ~1% of the true count for large cardinalities at the default precision.

## Merge rule

`piece(a, b)` takes the element-wise maximum of the two register arrays. This is idempotent, commutative, and associative — so the same item added by two different replicas is counted only once after merging.

```
piece({2, 1, 4, …}, {1, 3, 4, …}) = {2, 3, 4, …}   // max per register
```

## Precision

The `precision` parameter `p` controls the number of registers `m = 2^p`:

| `p` | Registers | Memory | Relative error |
|-----|-----------|--------|----------------|
| 10  | 1,024     | 1 KB   | ~3.3%          |
| 14  | 16,384    | 16 KB  | ~0.8% (default) |
| 18  | 262,144   | 256 KB | ~0.2%          |

All replicas in a group must use the same precision.

## Code examples

**Basic usage — count distinct visitors:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleHyperLogLog -->
```kotlin
```
{ src="../../kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt" include-symbol="sampleHyperLogLog" }

**Two replicas merge without sharing item lists:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleHyperLogLogMerge -->
```kotlin
```
{ src="../../kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt" include-symbol="sampleHyperLogLogMerge" }

**Accuracy within 10% at p=14 over 10,000 distinct items:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/HyperLogLogTest.kt" include-symbol="estimateIsWithinErrorBandFor10kDistinctItems" }

## When to use

Use `HyperLogLog` when you need "how many distinct X?" across the mesh but can't afford to keep the full list of X. Typical uses: unique peers seen, unique events processed, unique keys touched.

The estimate is approximate (within `1.04 / sqrt(m)`). If you need exact counts, use `GSet` or `ORSet` instead and accept the O(n) memory cost.
