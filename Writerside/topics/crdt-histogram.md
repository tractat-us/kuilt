# Histogram

Suppose you've promised that requests finish within 10 ms, 50 ms, or at worst
100 ms — and you want to know how many landed in each band. A histogram
answers "how are my measurements *distributed*?" when you already know the
ranges you care about: you pick the boundaries up front, and each recorded
value lands in exactly one bucket.

`Histogram` makes those bucket counts mergeable: every device counts its own
measurements, any two histograms merge into one, and the merged histogram is
exactly what one machine would have counted had it seen everything. No
central aggregator, no double-counting when a message is delivered twice.

**Converges to:** the per-bucket counts (and running sum) of every value
recorded on every replica — exactly, not approximately.

## Merging loses nothing

Each bucket's count is a [`GCounter`](crdt-gcounter.md) (every replica owns
its own slot, merged by maximum), and merging two histograms merges the
counts bucket by bucket. That makes the merge idempotent, commutative, and
associative — a true CRDT, robust to kuilt's drop/duplicate/reorder delivery —
and **lossless**: the merged histogram equals the histogram of the combined
stream, in any merge order, merged any number of times.

The boundaries are a **cluster-wide constant**: two histograms merge only if
their boundaries match exactly — fix them once per deployment, like
`HyperLogLog`'s precision and `DDSketch`'s accuracy. `N` boundaries define
`N + 1` buckets with upper-inclusive edges: the first bucket is everything up
to and including the first boundary, and anything past the last boundary
falls into a final catch-all bucket.

## Code examples

**Count request latencies against SLA thresholds:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleHistogram -->
```kotlin
val replica = ReplicaId("api-server-1")

// Buckets: (-inf, 10], (10, 50], (50, 100], (100, +inf) — SLA thresholds in ms.
var latencies = Histogram.empty(boundaries = listOf(10.0, 50.0, 100.0))

// record() returns a one-bucket delta; absorb it with piece().
for (ms in listOf(7.0, 12.0, 45.0, 50.0, 220.0)) {
    latencies = latencies.piece(latencies.record(replica, ms))
}

check(latencies.bucketCounts == listOf(1L, 3L, 0L, 1L)) // 50.0 is upper-inclusive in (10, 50]
check(latencies.count == 5L)
check(latencies.sum == 334.0)
```

**Two servers merge their histograms losslessly:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleHistogramMerge -->
```kotlin
val serverA = ReplicaId("server-a")
val serverB = ReplicaId("server-b")
val boundaries = listOf(10.0, 100.0)

// Two servers count their own request latencies.
var a = Histogram.empty(boundaries)
var b = Histogram.empty(boundaries)
repeat(30) { a = a.piece(a.record(serverA, 5.0)) } // 30 fast requests
repeat(20) { b = b.piece(b.record(serverB, 500.0)) } // 20 slow requests

// Merge: pointwise GCounter join of the bucket counts.
val merged = a.piece(b)
check(merged.bucketCounts == listOf(30L, 0L, 20L))
check(merged.count == 50L)

// Idempotent: merging again with either side changes nothing.
check(merged.piece(a) == merged)
check(merged.piece(b) == merged)
```

## Details worth knowing

- **`sum` is mergeable too** — carried as a pair of grow-only double counters
  (positive and negative contributions), so the mean (`sum / count`) survives
  any merge order. `min`/`max` are deliberately *not* carried: they aren't
  products of grow-only counters, and would need their own min-/max-register
  lattices.
- **Deltas are tiny.** `record()` returns a patch touching a single bucket
  cell (plus a sum cell) — the same minimal sparse fragment idiom as the
  zoo's sketches. Re-delivered patches never inflate counts, because each
  cell is a per-replica `GCounter` slot.
- **OTel interop.** The state is structurally an OpenTelemetry
  `HistogramDataPoint` — explicit `bounds` plus `bucket_counts`, `count`, and
  `sum`. The OTLP mapping itself lives with the metrics exporter.

## When to prefer something else

- **You can't guess the range up front** (latency spanning orders of
  magnitude) — use [`DDSketch`](crdt-ddsketch.md), whose logarithmic buckets
  auto-cover whatever appears at uniform relative precision. Guess your
  explicit boundaries wrong and the interesting tail lands in one giant
  low-resolution bucket.
- **You need exact totals, not a distribution** — use
  [`GCounter`](crdt-gcounter.md) / [`PNCounter`](crdt-pncounter.md).
- **You need the current level, not a distribution** — use
  [`Gauge`](crdt-gauge.md).
