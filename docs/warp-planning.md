# `:kuilt-warp` — query planning

> Part of the [`:kuilt-warp`](warp-vision.md) dream. Read the [walk](warp-vision.md)
> first; this is one leaf of the [deeper-waters index](warp-deeper.md). Speculative
> (#665, spike #680); no commitment to build.

## Optimize for coordination, not IO

The **draft** is already a declarative dataflow value — which means it is also a
*query plan*, and the planner is just a **`Draft → Draft`** rewrite that runs
before the shuttle moves a byte. The familiar optimizer moves all apply, and CALM
hands the planner an unusual gift:

- **Pushdown** — run `filter`/projection on the peer that holds the data; less
  crosses the wire.
- **Reorder & fuse** — monotone operators *commute*, so the planner may freely
  reorder, fuse, and parallelize them. (Most planners must *prove* a reordering is
  safe; here it is safe by construction.)
- **Defer coordination** — push the one embroidery as *late and as small* as
  possible.

The twist is the **cost model**. A conventional planner minimizes IO. This one
minimizes **coordination** — consensus rounds — because in a peer-to-peer mesh a
Raft round is the expensive thing, not bytes or CPU. The optimizer's objective
function counts embroidery stitches.

And the recognitions cascade:

- **Statistics are CRDTs.** Cardinalities are mergeable HyperLogLog sketches —
  i.e. CRDTs — gossiped on the same anti-entropy. The stats layer is the zoo again.
- **Planning is itself ~coordination-free.** Each peer plans locally from the
  convergent draft + gossiped stats; no central optimizer. (Need one canonical
  plan? Electing it is just another embroidery stitch.)
- **It's incremental.** Monotone ⇒ results refine as data arrives; the query
  *converges* rather than *finishes*, and a threshold-read observes it whenever.

![Query planning as a Draft → Draft rewrite: the planner pushes filters down to the data, freely reorders and fuses the monotone stages because CALM lets them commute, and defers the single consensus step to be as late and small as possible. Its cost model minimizes coordination rounds rather than IO; its statistics are CRDTs (HyperLogLog), and planning itself is coordination-free.](images/warp/query-planner.svg)
