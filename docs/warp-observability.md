# `:kuilt-warp` — observability & OpenTelemetry

> Part of the [`:kuilt-warp`](warp-vision.md) dream. Read the [walk](warp-vision.md)
> first; this is one leaf of the [deeper-waters index](warp-deeper.md). An
> **externality** — where warp meets the outside observability ecosystem.
> Speculative (#665, spike #680); no commitment to build.

## Observability falls out of the zoo

Point the same move that builds the grid at the *running system* and the whole
observability stack appears with no new subsystem — just more of the CRDT zoo on
the same gossip:

- **Logs** — an append-only distributed log *is* an `Rga`: a convergent, ordered,
  append-only sequence. Every peer appends; the merges interleave into one order.
- **Metrics** — counters are `GCounter`/`PNCounter`, gauges are `LWWRegister`,
  unique-cardinality is a HyperLogLog sketch (a planned zoo addition, #693). All
  mergeable, all gossiped.
- **Traces** — causal dependency structure is exactly what the zoo's `Causal`
  carrier already tracks.

That last point is the deep one: **you don't instrument the trace, you infer it.**
Conventional tracing makes you propagate context and declare every parent/child
link by hand. But the `Causal` carrier already records *happens-before* for every
operation as it propagates — so the trace DAG can be **derived from the causality
that data movement already wrote down**. The links were never missing; they were a
byproduct. One honest qualifier: causal metadata captures *potential* causality
(A *could* have influenced B) — the full dependency cone, which is a superset of
hand-curated semantic spans. That superset is a gift for debugging (you see every
real dependency, not just the ones someone remembered to annotate) and can be
narrowed with explicit annotations where you want precision.

The horizontal payoff is stark: the `Causal` carrier is the *same* metadata that
makes CRDTs converge in the first place. Convergence and distributed tracing turn
out to be one mechanism read two ways — you don't build a tracer, you read the one
you already run.

## Bolting in OpenTelemetry

Bolting in OpenTelemetry is a thin adapter, not a rewrite. OTel supplies the
vocabulary (spans, trace/span ids, metric instruments) and the dashboards; kuilt
supplies a coordination-free, offline-first transport. Three seams:

1. A CRDT-backed `SpanExporter`/`MetricExporter`/`LogRecordExporter` that writes
   into the `Rga`/counter/`Causal` structures instead of POSTing OTLP — and OTel
   *cumulative* metric temporality maps cleanly onto monotone CRDT counters.
2. Propagate W3C `traceparent` inside the [**task descriptor**](warp-execution.md),
   so a trace follows the work as the shuttle carries it across peers — yet within
   the mesh the span links can be *read off the causal DAG* rather than hand-propagated.
3. An edge collector drains the converged CRDTs to OTLP for Jaeger/Prometheus/etc.

You keep standard instrumentation and backends; you trade real-time delivery for
eventually-consistent, brokerless convergence.

## The offline-first exporter (sketch)

The most *landable* version of all this is a single artifact: a **kuilt-backed,
offline-first OpenTelemetry exporter** for Kotlin Multiplatform — the platform
(wasm/iOS) and the capability (durable offline buffering) that OTel is thinnest on
today. The surface is deliberately small; the cleverness is all in the substrate.

```kotlin
// A kuilt-backed, offline-first OpenTelemetry exporter (Kotlin Multiplatform).
class WarpTelemetry(
    seam: Seam,            // the kuilt fabric — gossip + anti-entropy
    store: DurableStore,   // local WAL: SQLite (JVM/Android), IndexedDB (wasm), platform (iOS)
) {
    // The OTel SDK plugs these in as its exporters — standard instrumentation, unchanged:
    val spans  : SpanExporter        // span records → ORSet<Span> keyed by spanId  (idempotent union)
    val metrics: MetricExporter      // cumulative temporality → mergeable counters  (no double-count)
    val logs   : LogRecordExporter   // → Rga<LogRecord> (append-only, ordered)
}

// On any connected node, a bridge drains converged telemetry to a real backend:
class WarpOtlpBridge(seam: Seam, otlp: OtlpExporter)   // converged CRDTs → OTLP → Collector
```

The **key inversion**: each exporter's `export()` returns success the moment the
data is *durably written locally* — not when it's delivered. Delivery is the
fabric's job (gossip + anti-entropy), and it happens whenever connectivity allows,
possibly hours later. Reconnection isn't a blind queue replay; producer and
collector **reconcile by digest** — only the missing deltas move, which is kind to
a link that may drop again mid-sync.

![The offline-first exporter data path: an app's standard OTel SDK feeds kuilt-backed Span/Metric/Log exporters that persist into a durable local store as CRDTs (spans→ORSet, metrics→counter, logs→Rga); export() returns on the durable write, not on delivery; the kuilt fabric gossips and reconciles by digest on reconnect; an edge bridge drains the converged CRDTs to OTLP for a Collector and backends like Jaeger and Prometheus.](images/warp/offline-exporter.svg)

The CRDT representations are what make this correct rather than merely buffered:
spans are an `ORSet` keyed by `spanId` (resend = set union = automatic dedup);
metrics are mergeable cumulative counters (resend merges idempotently — the
delta-temporality double-count-under-retry bug *cannot occur*); logs are an `Rga`
ordered by producer sequence. **A resend can never corrupt the data**, which is the
property a flaky link otherwise destroys.

Honest limits, stated up front:

- **Clocks.** A long-offline device has its own possibly-skewed clock. You carry
  the producer's local timestamp and estimate an offset on reconnect (HLC helps
  order across producers), but you cannot recover ground truth.
- **Late traces.** A trace straddling an offline and an online producer only
  assembles when the offline half syncs; backends accept late spans only within an
  assembly window. Eventually-complete, with a latency ceiling.
- **Bounded buffer.** Offline-forever can't buffer forever — but the degradation is
  asymmetric: metrics compress losslessly (a counter is O(1) regardless of how many
  increments happened offline), while logs/traces degrade gracefully under a cap
  (windowed TTL, reservoir sampling) — and the exporter *logs what it dropped*,
  never silently truncating.
- **Trust.** On-device buffered telemetry is sensitive, and peer-relayed telemetry
  needs auth/encryption so a peer can't forge or read another's metrics. A later
  concern, but a real one.

**Why this one is different from the rest of the tree.** Everything else here is
gated on building `warp`. This is not. The exporter rides only on kuilt's
*existing* CRDT + gossip + anti-entropy, so it is buildable **today**, against a
real consumer, with no compute grid in sight — and it solves a problem real
multiplatform apps actually have: a product whose users are spread across many
endpoints (phones, browsers, desktops) currently has no good way to **reconcile
logs across all of them**. That makes the offline exporter the natural *first real
test* of these ideas — useful on its own, independent of the dream.

## Possible upstream contributions

If this ever became real, the same intersection suggests genuine OpenTelemetry
contributions (roughly best-fit, most-landable first):

1. The **offline-first KMP exporter sketched above** — covers the missing platform
   (wasm/iOS) *and* the missing capability (durable offline buffering), and the
   idempotent CRDT-counter merge is a principled fix for retry double-counting.
   Mostly shippable code plus a short semantics note. The strongest first move.
2. An OTEP for **causality-inferred span links** — derive the trace DAG from the
   `Causal` happens-before metadata instead of hand-propagating context. A
   data-model change (higher bar; socialise in the tracing SIG first).
3. **Hybrid-logical-clock** ordering for spans under wall-clock skew — a contained,
   valuable companion to either of the above.
