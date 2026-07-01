# Metrics

A metric is a **number you keep a running tally of** — how many requests you've served,
how many people are online right now, how much memory is in use. Where a trace is one
timed event and a log is one line of text, a metric is a total that moves up and down
over the life of your app.

There are a few flavours, and picking the right one is most of the job:

- **counts that only go up** — "requests served", "orders placed". You add to them.
- **levels that rise and fall** — "memory in use", "players online". You set them.
- **counts of distinct things** — how many *different* users showed up. Seeing the same
  user twice still counts as one.

The useful property: because a metric is stored as data that merges cleanly, re-sending
it after a dropped connection can never accidentally count something twice.

## Record a metric

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpMetricExporter -->

```kotlin
// A count that only goes up — re-sending after a dropped connection can't double-count.
val requests = MetricKey("server.requests", MetricKind.SUM, mapOf("handler" to "/api/v1"))
telemetry.metrics.incrementSum(requests, by = 1L)

// A count of *distinct* things — the same user seen twice is still one.
val users = MetricKey("unique.users", MetricKind.CARDINALITY)
telemetry.metrics.addCardinality(users, "user-abc")
telemetry.metrics.addCardinality(users, "user-abc") // ignored — already counted
```

## Where they go

Recording is only the first step. Your metrics are held safely on the device, survive
being offline, and reach your dashboard with no duplicates. That whole journey is on
**[Device to dashboard](observability.md)**.
