# `:kuilt-warp` — AI & modelling

> Part of the [`:kuilt-warp`](warp-vision.md) dream. Read the [walk](warp-vision.md)
> first; this is one leaf of the [deeper-waters index](warp-deeper.md). An
> **externality** — where warp meets the AI/modelling world. Speculative (#665,
> spike #680); no commitment to build.

## The CALM boundary draws the line

The same theorem that shapes the rest of the dream draws a sharp, *useful* line
through the AI space: warp is a decentralized substrate for the **aggregation-,
parallel-, and serving-shaped** ML workloads, and explicitly *not* for
tightly-coupled synchronous training. The boundary isn't an engineering limit —
it's CALM telling you which workloads can run coordination-free.

## Where it fits

- **Federated learning / analytics — the standout.** The *core* of FedAvg is
  monotone: accumulate `(Σweights, Σcount)` as a pair of counters — the *same*
  counters that carry [metrics](warp-observability.md), so the accumulation is one
  merge. Be precise about the boundary, though: the averaging itself
  (`Σweights / Σcount`) is a **read-time projection**, not a lattice op — a threshold
  read over the monotone accumulator, not part of the weave — and a standard FL
  *round* still has a **synchronization barrier** (wait for a client cohort, then
  aggregate), which is a coordination step, not eventual consistency. So warp suits
  the **outer loop** (accumulate-and-average across rounds, asynchronous variants),
  not a drop-in for the synchronized round. Brokerless, data stays on-device; kuilt
  reaches phones, browsers, and servers, so a peer-symmetric *multiplatform*
  substrate is genuinely differentiated — most FL frameworks are server-orchestrated
  and can't reach the browser or iOS at all. (Honest caveat: **secure aggregation**
  is *not* free here — it needs interactive cryptographic rounds, masking and
  key-agreement, which are not monotone and don't ride the counters.)
- **Distributed inference / batch scoring.** The search-and-rank hero example *is*
  this when `score` is a model forward pass: shuttle the inputs, weave the outputs.
  Models are big immutable blobs, so the [bobbin/creel](warp-execution.md) cache is
  model distribution and [compiler nodes](warp-execution.md) tier the runtime
  (ONNX/GGML compiled to a wasm kernel).
- **Hyperparameter / NAS / evolutionary search.** Parallel trials (shuttle), gather
  (weave), pick-best (the one embroidery stitch). A textbook fit.
- **Sharded vector search / RAG retrieval.** Each peer holds a shard of the index;
  scatter the query, merge partial top-k (a bounded join), aggregate. Ensembles and
  bagging are just averaging models — a merge.
- **Data prep / feature extraction** for training — map/reduce, monotone, dull, and
  exactly the shape warp is best at.

## Where it does *not* fit — and why

Tightly-coupled **synchronous SGD** training of one large model wants low-latency
all-reduce every step (HPC fabric — NCCL/InfiniBand), the *opposite* of eventual
consistency. A gossip-CRDT mesh paying a consensus round per step is the wrong
tool; the strength of the substrate (no coordination) is wasted when the algorithm
needs a global barrier on every iteration. The line is sharp: aggregation /
parallel / serving ML → yes; synchronized inner-loop training → no.

The honest framing, then: warp is a substrate for the **outer loop and the
embarrassingly-parallel fringe** of modern ML — federation, serving, search,
sweeps — across a heterogeneous fleet no HPC cluster can reach.
