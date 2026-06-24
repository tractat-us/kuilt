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
  i.e. CRDTs — gossiped on the same anti-entropy. The stats layer is the zoo again;
  and the *same* gossiped sketches are also your
  [metrics and telemetry](warp-observability.md) — paid for once, spent twice.
- **Planning is itself ~coordination-free.** Each peer plans locally from the
  convergent draft + gossiped stats; no central optimizer. (Need one canonical
  plan? Electing it is just another embroidery stitch.)
- **It's incremental.** Monotone ⇒ results refine as data arrives; the query
  *converges* rather than *finishes*, and a threshold-read observes it whenever.

![Query planning as a Draft → Draft rewrite: the planner pushes filters down to the data, freely reorders and fuses the monotone stages because CALM lets them commute, and defers the single consensus step to be as late and small as possible. Its cost model minimizes coordination rounds rather than IO; its statistics are CRDTs (HyperLogLog), and planning itself is coordination-free.](images/warp/query-planner.svg)

## The five pieces

The planning slice breaks into five sub-issues, in dependency order:

1. **Draft as a value** (`E1`) — *reify the computation*. Today
   `warp.shuttle(...).weave()` runs immediately; this captures it instead as an
   inspectable `Draft` — a dataflow graph of operations you can hold, walk, and
   rewrite *before* anything executes. It's the prerequisite for the rest: you
   can't optimize what you can't inspect. (Needs the [type seam](warp-execution.md)
   so a `Draft` knows which stages are monotone vs the embroidery.)

2. **Rewrite rules** (`E2`) — *the transformations*. A library of `Draft → Draft`
   moves: **pushdown** (run filters/projections on the peer holding the data),
   **reorder & fuse** (merge adjacent monotone stages — CALM lets them commute, so
   it's always safe), and **defer the embroidery** (float the single consensus step
   as late and as small as possible). Each rule rewrites the graph; none changes the
   result.

3. **The cost model** (`E3`) — *which rewrites to apply*. The novel part, and the
   reason this isn't an off-the-shelf planner: the cost function counts **coordination
   rounds** (consensus / embroidery stitches), not bytes or CPU. The objective is
   "spend the fewest Raft rounds," so the planner prefers plans that shrink or defer
   the one expensive thing. (Consumes `E2`'s rules and `E4`'s stats.)

4. **Stats gossip** (`E4`) — *what the cost model reads*. A planner needs numbers:
   shard sizes, cardinalities, selectivity. These are gathered as **mergeable CRDT
   sketches** — HyperLogLog for distinct-counts, counters for sizes — gossiped on the
   same anti-entropy as everything else. (This is the same machinery as
   [observability metrics](warp-observability.md) — paid for once.)

5. **Incremental / threshold-read execution** (`E5`) — *how the plan runs*. Because
   every stage is monotone, the query never "finishes" — it **converges**, refining
   as more data arrives (differential-dataflow style). `E5` is the execution side of
   that: results that update incrementally, plus **threshold reads** (LVar-style) so a
   consumer can ask "has it crossed X yet?" without waiting for a completion that
   never comes.

Built on `E1`; `E1 → E2 → E3`, with `E4` feeding `E3` and `E5` an independent
consumer of `E1`. None of it ships until the [spike](warp-slices.md) (`0e`) proves
the core, and `E1` additionally needs the type seam.
