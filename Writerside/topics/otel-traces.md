# Traces

A trace answers one everyday question: *how long did that take, and what happened
along the way?* When someone taps **checkout** in your app, a trace is the stopwatch
that starts when the tap lands and stops when the order is confirmed — 480 ms later,
say — noting each step in between.

Each timed step is called a **span**. A span has a name (`checkout`), a start and end
time, and an optional parent, so a big operation can contain smaller ones — `checkout`
wrapping `charge card` and `reserve stock`. Laid end to end, spans show you exactly
where the time went, and when something is slow, which step to blame.

## Record a span

You record spans through one combined entry point, `WarpTelemetry`. `export()` finishes
the instant the span is safely on the device — it does not wait for a network.

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetry -->

```kotlin
val telemetry = WarpTelemetry(replica = ReplicaId("device-uuid-abc123"), store = InMemoryDurableStore())
telemetry.recover()   // bring back anything recorded before the app was last closed

telemetry.spans.export(span)   // one timed unit of work
```

## Where they go

Recording is only the first step. Your spans are held safely on the device, survive
being offline, and reach your dashboard with no duplicates — even carried off a phone
by another device that happened to be online. That whole journey is on
**[Device to dashboard](observability.md)**.
