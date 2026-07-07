# DDSketch

Suppose your app runs on many devices, and each one measures how long its
requests take. You want to answer: "what's the *typical* response time?" and
"how slow are the worst 1%?" — the median and the 99th percentile. Averages
won't do (one slow outlier hides in an average), and collecting every raw
measurement on one machine is exactly the kind of central bottleneck kuilt
avoids.

`DDSketch` lets each device keep a small summary — a *sketch* — of its own
measurements. Any two sketches can merge into one, and the merged sketch
answers percentile questions about the **combined** measurements as if one
machine had seen them all. You pick the precision up front: a sketch built with
1% relative accuracy answers any percentile within 1% of the true value —
whether that value is 2 milliseconds or 2 minutes.

**Converges to:** the quantile summary of every value recorded on every
replica, with each estimate within the configured relative accuracy (α) of the
exact quantile.

## Merging loses nothing

The key promise: merging is **lossless**. The sketch groups values into
logarithmic buckets (each bucket spans a fixed *ratio*, so precision is
relative, not absolute), and each bucket just counts how many values landed in
it. Merging two sketches merges the counts, bucket by bucket — the result is
*exactly* the sketch you would have built from the combined stream. Merge order
doesn't matter, merging twice doesn't matter, and no accuracy is lost at the
seams.

Each bucket's count is a `GCounter` (every replica owns its own slot, merged by
maximum), which is what makes the merge safe to repeat: a message delivered
twice never double-counts. That makes `DDSketch` a true CRDT — idempotent,
commutative, associative — robust to kuilt's drop/duplicate/reorder delivery.

Two sketches merge only if they were built with the same configuration
(accuracy and indexable range) — fix those once per deployment, like
`HyperLogLog`'s precision.

## Code examples

**Track latency percentiles on one node:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleDDSketch -->
```kotlin
val replica = ReplicaId("api-server-1")

// α = 0.01 → every quantile estimate is within 1% of the true value.
var latencies = DDSketch.empty(relativeAccuracy = 0.01)

// add() returns a one-bucket delta; absorb it with piece().
for (ms in listOf(12.0, 15.0, 14.0, 250.0, 13.0, 16.0, 900.0, 14.5)) {
    latencies = latencies.piece(latencies.add(replica, ms))
}

// The p50 sits among the fast requests; the p99 reflects the slow tail.
check(latencies.quantile(0.5) in 13.0..17.0)
check(latencies.quantile(1.0) in 890.0..910.0) // within 1% of 900
```

**Two servers merge their sketches losslessly:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleDDSketchMerge -->
```kotlin
val serverA = ReplicaId("server-a")
val serverB = ReplicaId("server-b")

// Two servers record their own request latencies.
var a = DDSketch.empty()
var b = DDSketch.empty()
repeat(100) { a = a.piece(a.add(serverA, 10.0 + it)) }   // 10–109 ms
repeat(100) { b = b.piece(b.add(serverB, 500.0 + it)) }  // 500–599 ms

// Merge: pointwise GCounter join of the bucket counts.
val merged = a.piece(b)
check(merged.count == 200L)

// The merged p50 sits at the boundary between the two servers' ranges.
check(merged.quantile(0.5) in 100.0..120.0)

// Idempotent: merging again with either side changes nothing.
check(merged.piece(a) == merged)
check(merged.piece(b) == merged)
```

## How the guarantee works

With relative accuracy α, the bucket boundaries grow by a constant factor
γ = (1+α)/(1−α): bucket `i` covers values in `(γ^(i−1), γ^i]`. A value `v` is
filed under index `⌈log_γ v⌉`, and a quantile query returns the bucket's
representative value `2γ^i/(γ+1)` — the point whose worst-case relative error
over the bucket is exactly α at both edges. Negative values use a mirrored
bucket store; zeros are counted exactly in their own slot.

Memory is bounded without breaking the merge: values smaller in magnitude than
`minIndexedValue` count as zeros, and values larger than `maxIndexedValue`
clamp into the top bucket **and** increment a mergeable `overflowCount` — so a
too-narrow range is observable, never silent. At the defaults (α = 0.01, range
10⁻⁹…10¹⁸) the sketch holds at most ≈3110 buckets per sign, and only buckets
that actually receive values exist at all.

This is the algorithm from *DDSketch: A Fast and Fully-Mergeable Quantile
Sketch with Relative-Error Guarantees* (Masson, Rim, Lee — PVLDB 2019), with
the bucket counts lifted into `GCounter`s so the merge is idempotent as well as
lossless. The state maps one-to-one onto OpenTelemetry's
`ExponentialHistogramDataPoint` (zero count + positive/negative log-bucket
arrays), which is what makes it the natural mergeable backing for latency
metrics.

## When to prefer something else

- **You know the bucket boundaries you want** (fixed SLA thresholds, say) —
  [`Histogram`](crdt-histogram.md) counts against explicit buckets exactly and
  maps to OTel's explicit-bucket `Histogram`.
- **You need exact counts, not a distribution** — use
  [`GCounter`](crdt-gcounter.md) / [`PNCounter`](crdt-pncounter.md).
- **You need distinct-counting or frequency, not quantiles** — those are
  [`HyperLogLog`](crdt-hyperloglog.md) and
  [`CountMinSketch`](crdt-countminsketch.md).
