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

---

The split mirrors the dream's own shape: the engine is more *recognition*
(it's already built), the externalities are more *fantasy* (where it could reach).
Each leaf links back here and to the [walk](warp-vision.md).
