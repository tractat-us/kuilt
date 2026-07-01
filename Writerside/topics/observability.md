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

The rest of this page walks the whole path, in the order you'll build it:

1. **[Record](#record)** the notes as your app runs.
2. They **[survive being offline](#offline)** and sort themselves out across devices.
3. **[See them in a dashboard](#dashboard)** — deliver them to your telemetry backend.

## 1. Record what your app is doing {id="record"}

This is a normal logging/metrics setup — you record events as your app runs, and
they show up later. There are three kinds of note, and kuilt records all of them
through one combined entry point, `WarpTelemetry`:

- **traces** — one timed unit of work ("this checkout took 480 ms"),
- **metrics** — numbers you count or sample ("requests served", "current memory"),
- **logs** — text lines ("user checked out").

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetry -->

```kotlin
val telemetry = WarpTelemetry(replica = ReplicaId("device-uuid-abc123"), store = InMemoryDurableStore())

// Recover anything recorded during a previous session (e.g. before the app was closed).
telemetry.recover()

// export() returns the moment the note is safely on the device —
// NOT when it reaches a server. Delivery happens later, on its own.
telemetry.spans.export(span)         // a span: one timed unit of work
telemetry.logs.export(logRecord)     // a log line
```

Metrics read a little differently, because a metric is a running total rather than a
one-off event — you nudge it up, set it, or add to it:

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpMetricExporter -->

```kotlin
// Count server requests — re-sending after a dropped connection can't double-count.
val requests = MetricKey("server.requests", MetricKind.SUM, mapOf("handler" to "/api/v1"))
telemetry.metrics.incrementSum(requests, by = 1L)

// Count *distinct* users — the same user seen twice is still one.
val users = MetricKey("unique.users", MetricKind.CARDINALITY)
telemetry.metrics.addCardinality(users, "user-abc")
telemetry.metrics.addCardinality(users, "user-abc") // ignored — already counted
```

The one thing to notice: every call above finishes the instant the note is written
to local storage. None of them wait for a network. That's the whole idea, and the
next section is why it's safe.

## 2. It survives being offline {id="offline"}

The industry-standard way for apps to emit this kind of data is called
**OpenTelemetry** — a shared vocabulary for exactly those three kinds of note
(traces, metrics, logs), plus the dashboards that read them (Jaeger, Prometheus, and
friends). `kuilt-otel` speaks that same vocabulary, so the data you record lands in
the tools you already use.

What it changes is *when a record is considered done*. A normal setup sends each
note to a collector over the network and fails if the network is down. `kuilt-otel`
instead succeeds the moment the note is **durably written to local storage**. Getting
it onward is a separate step that happens whenever a connection is available —
possibly much later, and possibly from a *different device* (more on that below).

That "no double-counting" property isn't politeness — it's structural. Every kind of
note is stored as a [replicated data type](crdt-overview.md): traces as a set keyed
by id, metrics as mergeable counters, logs as an ordered append-only sequence.
Re-sending something you already sent is a merge with itself, which changes nothing.
The classic "my retry counted the request twice" bug simply can't happen here.

The same machinery reconciles notes *between devices*. Two devices that briefly meet
exchange only the records the other is missing — so telemetry recorded on a phone
that never once reached your servers can still arrive, carried by another device that
did. A brief window of connectivity is enough.

## 3. See it in a dashboard {id="dashboard"}

Recording is half the job; eventually you want to *look* at the data. This is the
step that delivers it onward to your telemetry backend — Jaeger, an OpenTelemetry
Collector, whatever you already run.

OpenTelemetry backends receive data over a common wire protocol, **OTLP** (the
OpenTelemetry Protocol). You write one small adapter — an `OtlpEdge` — that knows how
to talk OTLP to *your* backend, and hand it to a `WarpOtlpBridge`. The bridge does the
careful part: on every reconnect it asks the backend what it already has, and sends
only what's missing.

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpOtlpBridge and #sampleOtlpEdge -->

```kotlin
// You implement OtlpEdge once — a small adapter that speaks OTLP to your collector.
// digest(): ask the backend which records it already holds.  send(): hand it the rest.
// Logs and metrics have the same pair — logDigest()/sendLogs(), metricDigest()/sendMetrics() —
// with no-op defaults, so you override only the signals your backend accepts.
class MyCollectorEdge(private val endpoint: String) : OtlpEdge {
    override suspend fun digest(): SpanDigest = /* GET  $endpoint/v1/traces/digest */
    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) = /* POST $endpoint/v1/traces */
}

// The bridge drains all three signals — spans, logs, metrics — reconciling each by digest.
val bridge = WarpOtlpBridge(telemetry, Clock.System)

// Safe to call on every reconnect: the bridge compares against the backend's digest
// and sends only what's missing, so a resend can never double-count or re-flood.
when (val result = bridge.drain(MyCollectorEdge("https://otel-collector.example.com"))) {
    is DrainResult.Success -> log.info {
        "delivered ${result.spansSent} span(s), ${result.logsSent} log(s), ${result.metricPointsSent} metric point(s)"
    }
    is DrainResult.Failure -> { /* backend unreachable — just try again next reconnect */ }
}
```

Because any device that reaches the backend can drain what it holds — including
records that synced over from peers — you don't need every device online at once. One
device with a connection can carry the room's telemetry to your dashboard.

> **All three signals travel this path** — traces, metrics, and logs. The bridge reconciles
> each one independently by digest, so every device delivers exactly what it holds and never
> the same record twice. If your backend speaks OTLP over HTTP, you don't even write the
> adapter: `:kuilt-otel-otlp` ships a ready-made `OtlpHttpEdge` that POSTs to `/v1/traces`,
> `/v1/logs`, and `/v1/metrics`.

## Where it runs, and the honest limits

It works on every platform kuilt targets — phones (Android, iOS), desktop and servers
(JVM, macOS), and the browser (wasm) — each with crash-safe local storage underneath.

A few things to know going in: timestamps come from each device's own clock, so a
long-offline device with a skewed clock can mis-order against its peers; a trace that
straddles an offline and an online device only completes once the offline half syncs;
and local storage is bounded — when a cap is hit, evicted items are always *logged*,
never silently dropped.

## Going deeper

- **[Capturing &amp; pulling logs](log-capture.md)** — record the logs your app
  *already* writes into this same offline buffer, then reach into an otherwise-
  unreachable device (a phone or a CI simulator) and pull them off from a test.
- **[Offline-first OpenTelemetry — the design](https://github.com/tractat-us/kuilt/blob/main/docs/offline-otel.md)**
  — why the replicated-data representation makes a resend safe, the local-write
  inversion, the digest-reconciled delivery, and the full limits.
- **[Re-discovering which step led to which](https://github.com/tractat-us/kuilt/blob/main/docs/otel-causal-links.md)**
  — kuilt already knows the happens-before order of events across every device, so it
  can reconnect traces that lost their thread across an offline gap — labelled
  *potential*, never overclaimed.
- **[API reference](https://tractat-us.github.io/kuilt/api/)** — every type in
  `kuilt-otel`, with runnable examples.
