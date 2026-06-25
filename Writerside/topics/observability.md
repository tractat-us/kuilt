# Observability

When your app is running on someone's phone, you want to know what it's actually
doing — did the screen load, did the payment go through, how long did it take, did
anything go wrong? Apps record little notes about all of this as they run. Those
notes are how you tell whether things are healthy or broken.

The hard part is collecting those notes from real users. Phones go through tunnels.
Browsers get closed. Laptops sleep. When a device is offline, the usual approach
just *loses* whatever it couldn't send.

kuilt takes a different path: **write the note down safely on the device first, and
deliver it whenever the network comes back** — minutes or hours later. Nothing is
lost while offline, and — this is the part that's normally hard — **nothing is
counted twice** when a flaky connection makes the device send the same note again.

## How you'd use it

This is a normal logging/metrics setup — you record events as your app runs, and
they show up in your dashboards later. Here's recording a few measurements:

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpMetricExporter -->

```kotlin
val exporter = WarpMetricExporter(replica = ReplicaId("device-uuid-abc123"), store = InMemoryDurableStore())

// Recover anything recorded during a previous session (e.g. before the app was closed).
exporter.recover()

// Count server requests — re-sending after a dropped connection can't double-count.
val requests = MetricKey("server.requests", MetricKind.SUM, mapOf("handler" to "/api/v1"))
exporter.incrementSum(requests, by = 1L)

// Count distinct users — the same user seen twice is still one.
val users = MetricKey("unique.users", MetricKind.CARDINALITY)
exporter.addCardinality(users, "user-abc")
exporter.addCardinality(users, "user-abc") // ignored — already counted
```

Recording a trace (one unit of work, with timings) and a log line works the same
way, through one combined facade:

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetry -->

```kotlin
val telemetry = WarpTelemetry(replica = ReplicaId("device-uuid-abc123"), store = InMemoryDurableStore())
telemetry.recover()

// export() returns the moment the data is safely on the device —
// NOT when it reaches a server. Delivery happens later, on its own.
telemetry.spans.export(span)   // a span: one timed unit of work
telemetry.logs.export(logRecord)
```

## What's actually going on

The industry-standard way for apps to emit this kind of data is called
**OpenTelemetry** — a common vocabulary of *traces* (timed units of work), *metrics*
(numbers like counts and gauges), and *logs* (text events), plus the dashboards that
read them (Jaeger, Prometheus, and friends). `kuilt-otel` plugs into OpenTelemetry as
a standard *exporter*, so your existing instrumentation and dashboards keep working
unchanged.

The one thing it changes is *when an export is considered done*. A normal exporter
sends the data to a collector over the network and fails if the network is down.
`kuilt-otel` instead succeeds the moment the data is **durably written to local
storage**. Getting it to a backend is then the network fabric's job — gossip and
anti-entropy carry it across whenever a connection is available, possibly much later.
Two reconnecting devices exchange only the notes the other is *missing*, so a brief
window of connectivity is enough.

That "no double-counting" property isn't politeness — it's structural. Every kind of
signal is stored as a [replicated data type](crdt-overview.md): spans as a set keyed
by id, metrics as mergeable counters, logs as an ordered append-only sequence.
Re-sending an item you already sent is a merge with itself, which changes nothing. The
classic "my retry counted the request twice" bug simply can't happen here.

## Where it runs, and the honest limits

It works on every platform kuilt targets — phones (Android, iOS), desktop and servers
(JVM, macOS), and the browser (wasm) — each with crash-safe local storage underneath.

A few things to know going in: timestamps come from each device's own clock, so a
long-offline device with a skewed clock can mis-order against its peers; a trace that
straddles an offline and an online device only completes once the offline half syncs;
and local storage is bounded — when a cap is hit, evicted items are always *logged*,
never silently dropped.

## Going deeper

- **[Offline-first OpenTelemetry — the design](https://github.com/tractat-us/kuilt/blob/main/docs/offline-otel.md)**
  — why the replicated-data representation makes a resend safe, the local-write
  inversion, and the full limits.
- **[API reference](https://tractat-us.github.io/kuilt/api/)** — every type in
  `kuilt-otel`, with runnable examples.
