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

- **Federated learning / analytics — the standout.** FedAvg is a count-normalized
  sum of model updates: accumulate `(Σweights, Σcount)` as monotone counters and
  divide at read — that's a *weave*. Brokerless, data stays on-device, no central
  server. kuilt already reaches phones, browsers, and servers, so a peer-symmetric
  *multiplatform* federated substrate is genuinely differentiated — most federated
  frameworks are server-orchestrated, and most can't reach the browser or iOS at
  all. Secure aggregation rides the same monotone accumulation.
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
