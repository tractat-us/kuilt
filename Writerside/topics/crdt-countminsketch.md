# Count-Min Sketch

Track how often each item appears across a stream — without storing every item. A Count-Min sketch answers "how many times did X show up?" using a fixed amount of memory, no matter how many different items you've seen.

**Converges to:** a frequency estimate that never underestimates the true count and stays within a configurable error band with high probability.

## When to use

Use `CountMinSketch` when you need approximate frequency counts over a large or unbounded key space — analytics, rate-limiting, detecting hot items, or adaptive routing over a P2P mesh. For detecting presence without frequency, see the Bloom-filter-like `GSet`. For exact counts on a small key space, use `GCounter`.

## How it works

The sketch holds a `depth × width` matrix of counters. Each of the `depth` rows uses an independent hash. Adding an item increments one cell per row; estimating returns the minimum across rows — the cell least inflated by hash collisions.

```
add("hello"):    row 0 → col 3  (+1)
                 row 1 → col 17 (+1)
                 row 2 → col 8  (+1)
                 row 3 → col 41 (+1)

estimate("hello"): min(cells[0][3], cells[1][17], cells[2][8], cells[3][41])
```

Larger `width` → lower error rate (`ε = e/width`). Larger `depth` → lower failure probability (`δ = e^-depth`). Width 512, depth 5 gives `ε ≈ 0.005, δ ≈ 0.007`.

## Merge rule

[piece] takes element-wise **max** of the two matrices. Max-merge is idempotent: a re-delivered patch never inflates the count beyond its highest seen value. This is the standard convergent (CRDT-safe) variant of Count-Min.

```
merge({[3,1,0]}, {[1,2,0]}) = {[3,2,0]}   // max per cell
```

Additive merge is **not** used — it would double-count on re-delivery and break convergence.

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

## Error bound

The standard Count-Min guarantee: the probability that an estimate exceeds the true count by more than `ε × N` (where `N` = total items added) is at most `δ`:

| width | depth | ε (error rate) | δ (failure prob) |
|-------|-------|---------------|-----------------|
| 256   | 4     | ≈ 0.011       | ≈ 0.018         |
| 512   | 5     | ≈ 0.005       | ≈ 0.007         |
| 1024  | 7     | ≈ 0.003       | ≈ 0.001         |

The estimate is always ≥ the true count (never underestimates).

## Hash family

One multiply-shift hash per row, seeded with a Fibonacci-derived constant `(row + 1) × 2654435761`. Entirely self-contained — no external dependencies. Not cryptographically secure; suitable for frequency sketching only.
