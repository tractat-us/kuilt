# Count-Min Sketch

Imagine three servers handling search traffic. Each one sees a river of queries —
"running shoes", "bluetooth headphones", "running shoes" again. At the end of the
hour, you want to know: which queries are trending? Which items are the heavy
hitters?

You could keep a counter for every query. But the number of distinct queries is
enormous, and storing them all across three servers — then reconciling the counts
— costs memory proportional to your vocabulary. For millions of distinct strings
that's impractical.

`CountMinSketch` is a different trade-off: each server keeps a small, fixed-size
grid of counters — a few kilobytes — and can answer "roughly how many times did
this query appear?" for *any* query, no matter how many distinct queries have
flowed through. The same grid covers a thousand queries or a billion.

**Converges to:** a frequency estimate that never underestimates the true count
and stays within a configurable error band with high probability.

## The one honest caveat — it can overcount, never undercount

A Count-Min sketch answers "how many times did X appear?" with one promise and
one caveat:

- **"At least this many"** → always correct. The estimate is always ≥ the true count.
- **"Exactly this many"** → sometimes a little high. The estimate can overshoot,
  bounded by a configurable error fraction of the total traffic seen.

In everyday terms: if "running shoes" truly appeared 1 000 times, the sketch
might say 1 030 — but it will never say 970. That guarantee is absolute.

The overshoot is caused by *hash collisions*: different queries mapping to the
same counter cell. The sketch compensates by using several independent rows and
taking the minimum across them — the row least contaminated by collisions. With
sensible defaults (width 512, depth 5) the overshoot stays below 0.5% of total
traffic, with only a 0.7% chance of exceeding even that.

## Merging without double-counting across replicas

When the three servers synchronise, the merged sketch reflects the *most* any
single replica observed for each item. It does **not** add the counts together.

This is what makes `CountMinSketch` a CRDT: `piece(a, b)` takes the
element-wise **maximum** of the two counter grids. An item that both replicas
counted pushes the same cells in both; taking the max is the same as if only one
replica — whichever saw it more — had counted it. Merging is idempotent,
commutative, and associative.

```
merge({[3,1,0]}, {[1,2,0]}) = {[3,2,0]}   // max per cell
```

Merge two servers' sketches → estimate the peak frequency from either. Merge
again with the same sketch → nothing changes.

**Why max and not sum?** Additive merge would inflate every re-delivered patch:
if replica A syncs its counts to B, then B syncs back, the same observations
get counted twice. Max-merge is idempotent — you can re-deliver a patch safely.
It also means the merged estimate reflects whichever replica saw the item most
often, not the combined total across all replicas. If you need an additive total
across independently operating replicas, use separate `GCounter` instances keyed
by item — Count-Min's strength is the *unbounded key space*, not cross-replica
summation.

## Code examples

**Basic usage — add items and estimate frequency:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleCountMinSketch -->

```kotlin
// width=512, depth=5 → ε ≈ 0.005, δ ≈ 0.007 error bound.
var sketch = CountMinSketch.empty(width = 512, depth = 5)

// add() returns a delta; absorb it with piece().
repeat(10) { sketch = sketch.piece(sketch.add("hello")) }
repeat(3) { sketch = sketch.piece(sketch.add("world")) }

check(sketch.estimate("hello") >= 10L)  // never underestimates
check(sketch.estimate("world") >= 3L)
check(sketch.estimate("unseen") == 0L)  // empty sketch returns 0
```

**Merging replicas — max-merge is idempotent:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleCountMinSketchMerge -->

```kotlin
var a = CountMinSketch.empty(width = 64, depth = 4)
var b = CountMinSketch.empty(width = 64, depth = 4)

// Two replicas observe different occurrences of the same item.
repeat(7) { a = a.piece(a.add("event")) }
repeat(4) { b = b.piece(b.add("event")) }

// After merging, the merged estimate is >= the max of the two.
val merged = a.piece(b)
check(merged.estimate("event") >= 7L)

// Merging again is idempotent — same result.
check(merged.piece(a) == merged.piece(a).piece(a))
```

## How it works: the depth × width grid

Under the hood, a `CountMinSketch` holds a `depth × width` matrix of integer
counters, all starting at zero. Each of the `depth` rows uses an independent
hash function.

**Adding an item** hashes it into one column per row and increments those cells:

```
add("hello"):    row 0 → col 3  (+1)
                 row 1 → col 17 (+1)
                 row 2 → col 8  (+1)
                 row 3 → col 41 (+1)
```

**Estimating an item** hashes it the same way and returns the minimum value
across those cells — the reading least inflated by collisions from other items:

```
estimate("hello"): min(cells[0][3], cells[1][17], cells[2][8], cells[3][41])
```

Different items occasionally collide in the same cell (one may bump the other's
counter), but the chance of colliding across *all* rows simultaneously is low —
and gets lower as `width` grows. The minimum-across-rows trick is what keeps the
estimate close to the truth.

**Merging** takes the element-wise maximum of the two matrices:

```
piece({[3,1,0]}, {[1,2,0]}) = {[3,2,0]}   // max per cell
```

Max preserves idempotence: a cell's value can only move up as new observations
arrive; re-merging the same state never increases it further. This makes the
structure a valid join-semilattice — the mathematical requirement for a CRDT.

## Error bound and sizing

The standard Count-Min guarantee: the estimate exceeds the true count by more
than `ε × N` (where `N` = total items added) with probability at most `δ`.

- `ε = e / width` — the relative error rate, controlled by how wide each row is.
- `δ = e^−depth` — the failure probability, controlled by how many rows there are.

| width | depth | ε (error rate) | δ (failure prob) |
|-------|-------|----------------|-----------------|
| 256   | 4     | ≈ 0.011        | ≈ 0.018         |
| 512   | 5     | ≈ 0.005        | ≈ 0.007         |
| 1024  | 7     | ≈ 0.003        | ≈ 0.001         |

The estimate is always ≥ the true count (never underestimates). All replicas
that share a sketch must use the same `width` and `depth`; `piece()` throws
`IllegalArgumentException` if they differ.

## Hash family

One multiply-shift hash per row, seeded with a Fibonacci-derived constant
`(row + 1) × 2654435761`. Entirely self-contained — no external dependencies.
Not cryptographically secure; suitable for frequency sketching only.

## When to use

- Which search queries are trending right now across all servers?
- Which items appear most often in a stream too large to fit in memory?
- Which source IPs are generating the most traffic? (Rate limiting, anomaly detection.)
- Which topics is a P2P gossip mesh relaying most frequently?

Use `CountMinSketch` when you need approximate frequency counts over a large or
unbounded key space and bounded, fixed memory matters more than exact precision.
The never-underestimate guarantee is absolute; the overcount is small and
configurable.

For detecting *presence* without frequency, see [BloomFilter](crdt-bloomfilter.md).
For *distinct count* rather than frequency, see [HyperLogLog](crdt-hyperloglog.md).
For exact counts on a small, known key space, use [GCounter](crdt-gcounter.md).
