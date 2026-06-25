# `:kuilt-warp` — observability & OpenTelemetry

> Part of the [`:kuilt-warp`](warp-vision.md) dream. Read the [walk](warp-vision.md)
> first; this is one leaf of the [deeper-waters index](warp-deeper.md). An
> **externality** — where warp meets the outside observability ecosystem.
> Speculative (#665, spike #680); no commitment to build.
>
> **The shipped, non-speculative slice of this idea already exists** as the
> `kuilt-otel` module — an offline-first OpenTelemetry exporter built on kuilt's
> existing CRDT + gossip + anti-entropy. For that, see
> [offline-otel.md](offline-otel.md) and the guide's
> [Observability](https://tractat-us.github.io/kuilt/guide/observability.html) page.
> This page keeps only the *further-out* ideas that genuinely depend on warp.

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

(The first two are *shipped* in `kuilt-otel`; the third — traces — is the
speculative leap below.)

## The deep one: you don't instrument the trace, you infer it

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
you already run. Within a warp mesh, a trace can follow the work as the shuttle
carries it across peers (via the W3C `traceparent` inside the
[task descriptor](warp-execution.md)) — yet the span links can be *read off the
causal DAG* rather than hand-propagated.

## Possible upstream contributions

If this ever became real, the same intersection suggests genuine OpenTelemetry
contributions (roughly best-fit, most-landable first):

1. The **offline-first KMP exporter** — covers the missing platform (wasm/iOS) *and*
   the missing capability (durable offline buffering), and the idempotent CRDT-counter
   merge is a principled fix for retry double-counting. *(This one is no longer
   speculative — it shipped as `kuilt-otel`; see [offline-otel.md](offline-otel.md).)*
2. An OTEP for **causality-inferred span links** — derive the trace DAG from the
   `Causal` happens-before metadata instead of hand-propagating context. A
   data-model change (higher bar; socialise in the tracing SIG first).
3. **Hybrid-logical-clock** ordering for spans under wall-clock skew — a contained,
   valuable companion to either of the above.
