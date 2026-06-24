# HyperLogLog

Imagine you run three web servers. Each one logs the user IDs it sees. At the
end of the day you want to know: how many distinct visitors came to the site in
total? You could collect every ID from every server into one giant list and count
the unique entries — but that costs memory proportional to the number of visitors,
and it means shipping the entire list over the network.

`HyperLogLog` is a different trade-off: each server keeps a *sketch* — a small
fixed-size array of about 16 KB — instead of the full list. The sketch is an
approximate counter that can answer "how many distinct items were added?" with
roughly 1% error. When two sketches merge, you get an estimate of the *combined*
distinct count without either server ever sharing its list. The same 16 KB covers
a hundred users or a hundred million.

**Converges to:** an estimate of the number of distinct items added across all
replicas. At the default precision (`p = 14`) the estimate is within ~0.81% of
the true distinct count for large cardinalities.

## Merging without double-counting

The key promise: merging two sketches gives the *union* cardinality. If server A
saw 1 000 users and server B saw 1 000 users with 500 in common, the merged sketch
estimates 1 500 — not 2 000. Items seen by both sides are not double-counted.

This is what makes `HyperLogLog` a CRDT: `piece(a, b)` takes the element-wise
maximum of the two register arrays. An item that both replicas added pushes the
same register values in both; taking the max is the same as if only one replica
had added it. Merging is idempotent, commutative, and associative — the same item
can arrive from a hundred replicas and the count stays correct.

```
piece({2, 1, 4, …}, {1, 3, 4, …}) = {2, 3, 4, …}   // max per register
```

Merge two servers' sketches → estimate the union's distinct count. Merge again
with the same sketch → nothing changes.

## Code examples

**Count distinct visitors on one node:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleHyperLogLog -->
```kotlin
var hll = HyperLogLog.empty(precision = 14)

// Add a stream of items — duplicates do not inflate the count.
hll = hll.add("alice")
hll = hll.add("bob")
hll = hll.add("alice") // duplicate — no effect

// The estimate is approximate but close to 2 for small cardinalities.
check(hll.estimate() in 1L..3L)
```

**Two replicas merge without sharing item lists:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleHyperLogLogMerge -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Replica A sees users 0–999; replica B sees users 500–1499 (500 in common).
var hllA = HyperLogLog.empty(precision = 14)
var hllB = HyperLogLog.empty(precision = 14)
repeat(1_000) { i -> hllA = hllA.add("user-$i") }
repeat(1_000) { i -> hllB = hllB.add("user-${i + 500}") }

// Merge: element-wise max of registers.
val merged = hllA.piece(hllB)

// The merged estimate is close to 1500 (the true distinct count).
val estimate = merged.estimate()
check(estimate in 1_200L..1_800L) { "expected ≈1500, got $estimate" }

// Idempotent: merging again with either replica changes nothing.
check(merged.piece(hllA) == merged)
check(merged.piece(hllB) == merged)
```

## How it works: registers and the leading-zero trick

Each `HyperLogLog` keeps an array of `m = 2^p` one-byte registers, all starting
at zero. When an item is added, it is hashed to a 32-bit value. The top `p` bits
select which register to update. The remaining bits are scanned from the
most-significant end, and the position of the first `1` bit (plus one) — called ρ
("rho") — is written to that register if it is larger than the current value.

Why does this estimate cardinality? A very rare hash (many leading zeros → large
ρ) signals that the sketch has probably seen a large number of items — you need
about 2^k attempts before you expect to see k consecutive leading zeros by chance.
By keeping the *maximum* ρ seen per register, the sketch implicitly records how
far it has searched. `estimate()` combines all registers via the harmonic-mean
formula from the HyperLogLog paper to turn those maximum observations into a count.

Taking element-wise max on merge is exactly the right join: for each register you
keep whichever replica observed the rarest hash, preserving everything both sides
collected.

### Small-cardinality correction

The raw harmonic-mean formula is inaccurate when many registers are still zero —
a sign that only a handful of distinct items have been added. In that regime
`estimate()` switches to *linear counting*: it counts the number of empty
registers and applies `m · ln(m / emptyRegisters)`, which is far more accurate
for small populations. This HLL++-style correction means you get reliable
estimates whether there are 10 distinct items or 10 million.

## Precision

The `precision` parameter `p` controls the number of registers `m = 2^p`:

| `p` | Registers | Memory  | Relative error       |
|-----|-----------|---------|----------------------|
| 10  | 1,024     | 1 KB    | ~3.3%                |
| 14  | 16,384    | 16 KB   | ~0.81% **(default)** |
| 18  | 262,144   | 256 KB  | ~0.2%                |

The theoretical relative error is `1.04 / sqrt(m)`. All replicas in a group must
use the same precision — `piece()` throws `IllegalArgumentException` if they
differ.

## Accuracy and the union guarantee

`estimate()` returns the number of distinct items added to *this* sketch. When
you call `piece(other)`, the result's `estimate()` approximates the number of
distinct items added to *either* sketch — the union. Overlapping items are counted
once. The same ~0.81% error bound applies to the merged sketch.

For exact distinct counts, use [GSet](crdt-gset.md) or [ORSet](crdt-orset.md) and
accept O(n) memory. For approximate membership rather than cardinality, see
[BloomFilter](crdt-bloomfilter.md).

## When to use

- How many unique users visited this feature across all servers today?
- How many distinct keys has the cluster touched since startup?
- How many unique peers have been observed across all mesh nodes?

Use `HyperLogLog` when the answer is "roughly how many?" and bounded, small memory
matters more than exact precision. The ~1% error at default settings is invisible
in analytics and monitoring contexts — and the sketch stays 16 KB whether you have
seen a thousand users or a billion.
