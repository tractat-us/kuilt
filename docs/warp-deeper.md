# `:kuilt-warp` — deeper waters

> Companion index to the [`:kuilt-warp`](warp-vision.md) dream. The vision doc is
> the **walk** — read it first. This is the map to the depth a reader reaches only
> after the relief. Still a dream (#665, spike #680); still no commitment to build.

The depth splits into the **internal engine** (how warp itself runs) and the
**externalities** (where it meets the outside world).

## The engine

- **[Execution](warp-execution.md)** — how a method travels (you ship a *name*, not
  code), shipping real code as sandboxed **WASM kernels**, the lazy **bobbin/creel**
  code cache (a Merkle-CRDT), and **compiler nodes** doing distributed tiered
  compilation.
- **[Query planning](warp-planning.md)** — the draft *is* a query plan, so the
  planner is a `Draft → Draft` rewrite whose cost model minimizes **coordination,
  not IO**.

## The externalities

- **[Observability & OpenTelemetry](warp-observability.md)** — logs, metrics, and
  traces fall out of the CRDT zoo; you *infer* the trace DAG from causality instead
  of instrumenting it; bolting in OTel is a thin adapter. *(Externality.)*
- **[AI & modelling](warp-ml.md)** — federated learning, distributed inference,
  hyperparameter search, and sharded retrieval — the aggregation-shaped ML the CALM
  boundary blesses, and the synchronous training it doesn't. *(Externality.)*

## The shared spine — one substrate, many payoffs

Notice how little does all this. The engine and the externalities aren't six
systems; they're six *payoffs* of the **same** machinery kuilt already ships —
CRDTs, gossip, and anti-entropy. Build that once (it's built) and the same
anti-entropy over a mergeable lattice cashes out everywhere:

- it **replicates state** — the core kuilt does today.
- it carries the **work-queue + results** — the [engine](warp-execution.md).
- it gossips **query statistics** (HyperLogLog) — the [planner](warp-planning.md).
- it converges **logs / metrics / traces** — [observability](warp-observability.md).
- it buffers and reconciles **offline telemetry** — the [offline exporter](warp-observability.md).
- it aggregates **federated-learning updates** — [AI & modelling](warp-ml.md).

That is the horizontal payoff: one piece of work pays off in six places. It holds a
level down, too — the **task descriptor** is one envelope doing three jobs (it
routes work, names the code [by content hash](warp-execution.md), and can carry the
[trace context](warp-observability.md)), and the **`Causal` carrier** does two at
once (it makes data converge *and* hands you the
[trace DAG for free](warp-observability.md)). Reading these docs, the question to
keep asking is: *what else does this primitive already pay for?*

The split also mirrors the dream's own shape — the engine is more *recognition*
(it's already built), the externalities more *fantasy* (where it could reach).
Each leaf links back here and to the [walk](warp-vision.md).
