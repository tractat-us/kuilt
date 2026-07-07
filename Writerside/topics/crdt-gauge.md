# Gauge

Some numbers are readings, not tallies: the temperature, the number of players
online, the memory in use right now. Yesterday's reading doesn't add to
today's — only the **latest** one matters. That's a gauge.

`Gauge` makes a reading mergeable across many devices: each device records
what it observed and when, and merging any two gauges keeps the newer
observation. Devices can observe independently, go offline, and sync later —
everyone converges on the most recent reading, no coordinator required.

**Converges to:** the observation with the newest timestamp seen anywhere,
with ties broken deterministically by replica id.

## Last writer wins

The state is one observation tagged `(timestamp, replicaId)`; merging keeps
the larger tag. That is exactly the [`LWWRegister`](crdt-lwwregister.md) join —
`Gauge` wraps a `LWWRegister<Double>` rather than inventing a second
convention — so the merge is idempotent, commutative, and associative, robust
to kuilt's drop/duplicate/reorder delivery. Two observations at the same
timestamp resolve by replica id, so every merge order gives the same answer.

You supply the timestamp — `Gauge` never reads a clock. Use the same monotonic
time source as the rest of your pipeline, and never reuse a
`(replica, timestamp)` pair. As with any last-writer-wins type, clock skew
between devices silently favours the faster clock; if that matters, pair with
a hybrid logical clock.

## Code example

**Two devices observe a level; merging keeps the newest reading:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGauge -->
```kotlin
val phone = ReplicaId("phone")
val laptop = ReplicaId("laptop")

// Each device observes the players-online level at its own time.
var onPhone = Gauge.empty()
var onLaptop = Gauge.empty()
onPhone = onPhone.piece(onPhone.observe(phone, timestamp = 100L, value = 4.0))
onLaptop = onLaptop.piece(onLaptop.observe(laptop, timestamp = 250L, value = 7.0))

// Merge: the observation with the larger (timestamp, replicaId) tag wins.
val merged = onPhone.piece(onLaptop)
check(merged.value == 7.0)
check(merged.timestamp == 250L)

// Commutative and idempotent: any merge order, any duplication, same answer.
check(onLaptop.piece(onPhone) == merged)
check(merged.piece(onPhone) == merged)
```

Apply local observations with `gauge = gauge.piece(gauge.observe(...))` — the
join guards against a belated older observation regressing a newer one.

The state maps one-to-one onto OpenTelemetry's `Gauge` metric point
(`NumberDataPoint`: the value plus its observation time), which is what makes
it the natural mergeable backing for level metrics.

## When to prefer something else

- **The number accumulates rather than being re-read** ("requests served") —
  use [`GCounter`](crdt-gcounter.md) / [`PNCounter`](crdt-pncounter.md).
- **You want concurrent observations surfaced instead of silently resolved** —
  use [`MVRegister`](crdt-mvregister.md).
- **You want the distribution of readings, not just the latest** — use
  [`Histogram`](crdt-histogram.md) or [`DDSketch`](crdt-ddsketch.md).
