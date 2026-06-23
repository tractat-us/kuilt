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
  unique-cardinality is HyperLogLog. All mergeable, all gossiped.
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

## Bolting in OpenTelemetry

Bolting in OpenTelemetry is a thin adapter, not a rewrite. OTel supplies the
vocabulary (spans, trace/span ids, metric instruments) and the dashboards; kuilt
supplies a coordination-free, offline-first transport. Three seams:

1. A CRDT-backed `SpanExporter`/`MetricExporter`/`LogRecordExporter` that writes
   into the `Rga`/counter/`Causal` structures instead of POSTing OTLP — and OTel
   *cumulative* metric temporality maps cleanly onto monotone CRDT counters.
2. Propagate W3C `traceparent` inside the **task descriptor**, so a trace follows
   the work as the shuttle carries it across peers — yet within the mesh the span
   links can be *read off the causal DAG* rather than hand-propagated.
3. An edge collector drains the converged CRDTs to OTLP for Jaeger/Prometheus/etc.

You keep standard instrumentation and backends; you trade real-time delivery for
eventually-consistent, brokerless convergence.

## Possible upstream contributions

If this ever became real, the same intersection suggests genuine OpenTelemetry
contributions (roughly best-fit first): an **OTel for Kotlin Multiplatform /
wasm / iOS** SDK + exporter (the KMP story is thin today); an **offline-first /
store-and-forward** telemetry path where idempotent CRDT-counter merge fixes
double-counting under retry; an OTEP for **causality-inferred span links**; and
**hybrid-logical-clock** ordering for spans under wall-clock skew.
