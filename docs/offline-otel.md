# Offline-first OpenTelemetry — the design

> The shipped design behind the **`kuilt-otel`** module — a real, buildable-today
> artifact, not part of the [`:kuilt-warp`](warp-vision.md) dream. The accessible
> introduction is the guide's [Observability](https://tractat-us.github.io/kuilt/guide/observability.html)
> page; the API is in the [Dokka reference](https://tractat-us.github.io/kuilt/api/).
> The speculative tail — inferring traces from causality, possible upstream
> contributions — lives in [warp-observability.md](warp-observability.md).

## The problem

A product whose users are spread across many endpoints — phones, browsers, desktops —
has no good way to **reconcile telemetry across all of them**. Standard
OpenTelemetry exporters POST spans to a collector and fail if the network is down.
On the platforms kuilt cares about most (wasm, iOS) that failure is common, and the
data is simply lost.

`kuilt-otel` is the most *landable* idea from the warp exploration, lifted out as a
single shippable module: a **kuilt-backed, offline-first OpenTelemetry exporter** for
Kotlin Multiplatform — covering the platform (wasm/iOS) and the capability (durable
offline buffering) that OpenTelemetry is thinnest on today. The surface is
deliberately small; the cleverness is all in the substrate.

## The shape

```kotlin
// A kuilt-backed, offline-first OpenTelemetry exporter (Kotlin Multiplatform).
class WarpTelemetry(
    replica: ReplicaId,    // this device's stable identity
    store: DurableStore,   // local WAL: SQLite/file (JVM/Android), IndexedDB (wasm), NSFileManager (iOS/macOS)
) {
    // The OTel SDK plugs these in as its exporters — standard instrumentation, unchanged:
    val spans  : WarpSpanExporter        // span records → ORSet<Span> keyed by spanId  (idempotent union)
    val metrics: WarpMetricExporter      // cumulative temporality → mergeable counters  (no double-count)
    val logs   : WarpLogRecordExporter   // → Rga<LogRecord> (append-only, ordered)
}

// On any connected node, a bridge drains converged telemetry to a real backend:
class WarpOtlpBridge(exporter: WarpSpanExporter)   // converged CRDTs → OTLP → Collector
```

## The key inversion

Each exporter's `export()` returns success the moment the data is *durably written
locally* — **not** when it is delivered. Delivery is the fabric's job (gossip +
anti-entropy), and it happens whenever connectivity allows, possibly hours later.
Reconnection isn't a blind queue replay; producer and collector **reconcile by
digest** — only the missing deltas move, which is kind to a link that may drop again
mid-sync.

## Why it is correct, not merely buffered

The CRDT representations are what make this safe rather than just queued:

- **Spans** are an `ORSet` keyed by `spanId` — a resend is a set union, so it is
  automatic dedup.
- **Metrics** are mergeable cumulative counters — a resend merges idempotently, so the
  delta-temporality double-count-under-retry bug *cannot occur*. A counter is also
  O(1) regardless of how long the device was offline (it compresses losslessly).
- **Logs** are an `Rga` ordered by producer sequence — append-only, idempotent merge.

**A resend can never corrupt the data**, which is the property a flaky link otherwise
destroys.

## The OTLP adapter

Bolting in OpenTelemetry is a thin adapter, not a rewrite. OTel supplies the
vocabulary (spans, trace/span ids, metric instruments) and the dashboards; kuilt
supplies a coordination-free, offline-first transport. Three seams:

1. CRDT-backed `SpanExporter`/`MetricExporter`/`LogRecordExporter` that write into the
   `ORSet`/counter/`Rga` structures instead of POSTing OTLP — and OTel *cumulative*
   metric temporality maps cleanly onto monotone CRDT counters.
2. Carry the W3C `traceparent` with the work so a trace follows it across devices.
3. An edge collector (`WarpOtlpBridge` + your `OtlpEdge`) drains the converged CRDTs to
   OTLP for Jaeger/Prometheus/etc.

You keep standard instrumentation and backends; you trade real-time delivery for
eventually-consistent, brokerless convergence.

![The offline-first exporter data path: an app's standard OTel SDK feeds kuilt-backed Span/Metric/Log exporters that persist into a durable local store as CRDTs (spans→ORSet, metrics→counter, logs→Rga); export() returns on the durable write, not on delivery; the kuilt fabric gossips and reconciles by digest on reconnect; an edge bridge drains the converged CRDTs to OTLP for a Collector and backends like Jaeger and Prometheus.](images/warp/offline-exporter.svg)

## Honest limits

- **Clocks.** A long-offline device has its own possibly-skewed clock. You carry the
  producer's local timestamp and can estimate an offset on reconnect (an HLC helps
  order across producers), but you cannot recover ground truth. A gauge from a
  slow-clock device may be overwritten by a faster-clock peer even when it was newer in
  wall time.
- **Late traces.** A trace straddling an offline and an online producer only assembles
  when the offline half syncs; backends accept late spans only within an assembly
  window. Eventually-complete, with a latency ceiling.
- **Bounded buffer.** Offline-forever can't buffer forever — but the degradation is
  asymmetric: metrics compress losslessly while logs/traces degrade gracefully under a
  cap, and the exporter *logs what it dropped*, never silently truncating.
- **Cardinality.** Unique-count metrics use HyperLogLog (~0.81% relative error at the
  default precision); precision is fixed per metric and changing it after production
  data exists is wire-breaking.
- **Trust.** On-device and peer-relayed telemetry needs auth/encryption so a peer can't
  forge or read another's metrics. Deferred to a later PR.
