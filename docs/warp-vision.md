# `:kuilt-warp` — a vision

> **Status: a dream. Highly speculative, unplanned, deliberately aspirational.**
> This is not a spec and not a commitment. It is a picture of the most beautiful
> thing `:kuilt-warp` could be, written so we can decide whether any of it is
> worth a throwaway spike (#680). Part of the research epic #665. Nothing here
> implies code will be written; if it ever is, only a small experiment.
>
> This page is the **walk** — read it top to bottom. The machinery (how code
> ships, how the system plans and observes itself, the AI applications) lives in
> the companion, [`warp-deeper.md`](warp-deeper.md), for after the relief.

## The one idea

What if running your code across a roomful of machines felt exactly like
running it on one?

You already know how to do a parallel map on a single computer: take a big list,
run a function over every item at once, and fold the answers together. `warp` is
a dream in which *that same line of code* runs across a whole cluster of peers —
phones, laptops, servers, whatever is in the room — and you can't tell the
difference. No servers to stand up, no job scheduler to configure, no message
queue, no coordinator. You write what looks like a local parallel map; a
mesh of devices runs it.

```kotlin
val warp   = warp(seam)                                  // the cluster, from one connection
val scores = warp.shuttle(corpus) { doc -> score(query, doc) }  // runs across every peer
                 .weave()                                // returning threads weave into cloth
```

That's the whole pitch. Everything below is the story of why those three lines
are *all you should ever have to write* — and the one place where the
mathematics says you have to write a little more.

## Why this isn't a fantasy: it's mostly already built

The reason this dream is even worth dreaming is that a compute grid needs four
things, and kuilt already ships all four. The vision is not "build a grid." It is
**"notice we already have one, and give it a face."**

| A compute grid needs… | …and kuilt already has |
|---|---|
| to know who's in the room, and who left | `:kuilt-liveness` (membership + failure detection) |
| to spread work and answers around | gossip dissemination (#652) |
| shared state that always agrees in the end | the CRDT zoo + `Quilter` |
| to balance load without a boss | the `BoundedCounter` equalizer (#643/#644/#667) |
| to agree *exactly* when agreement is unavoidable | `:kuilt-raft` |

The fourth row is the quiet punchline. The `BoundedCounter` equalizer we built to
keep quotas balanced is, mathematically, a **decentralized work-stealing
scheduler** — diffusive load balancing and power-of-two-choices, the exact
algorithms a grid uses to place tasks. We built a scheduler in disguise: rename
`BoundedCounter` to **`TaskScheduler`**, aim it at queue-depth instead of quota,
and *nothing else changes*.

So `:kuilt-warp` is a *thin reframing*, not a new engine. Every compute type is a
trivial wrapper around a primitive kuilt already ships:

```kotlin
class Warp(seam: Seam)                          // the grid — parallel lanes across the peers
class TaskQueue<T>(q: ORSet<Task<T>>)           // the work-queue   — a thin wrapper over an ORSet
class TaskScheduler(eq: BoundedCounter)         // places the work  — the equalizer, aimed at depth
class Results<R>(r: ORMap<Id, R>)               // the result store — a thin wrapper over an ORMap
// movement: Quilter anti-entropy already carries both the queue and the results.
```

That is the whole trick, and the doc keeps doing it on purpose: **name the grid
role, then reveal the CRDT under it.** A `TaskScheduler` is the equalizer; a
`TaskQueue` is an `ORSet`; `Results` is an `ORMap`. The compute layer is
*vocabulary*, not machinery — which is exactly why the only thing to build is the
thin wrappers.

![The recognition map: each grid role (TaskQueue, TaskScheduler, Results, the bobbin creel, transport, commit) is a thin wrapper over a kuilt primitive that already ships (ORSet, the BoundedCounter equalizer, ORMap, GSet+EphemeralMap, Quilter, :kuilt-raft). New code is just the wrappers.](images/warp/recognition-map.svg)

The textile metaphor finishes itself — and it stretches across the whole design.
A loom holds the **warp**: the parallel threads under tension. You load a
**shuttle** with the **weft** and throw it across, and the two weave into
**cloth**. Mapped onto the grid:

- **warp** — the parallel compute lanes spread across the peers (`warp(seam)`).
- **weft / shuttle** — `warp.shuttle(...)` throws each task across the warp;
  `.weave()` gathers the returning threads into **cloth**, the woven,
  coordination-free result. (`weave` *is* the reduce — a lattice join — but the
  metaphor-word earns its place over a generic `aggregate`.)
- **draft** — the *recipe*: how warp and weft interlace, declarable as a value
  before anything runs. (The natural word, *pattern*, is already a core type —
  `weave(Rendezvous.New(pattern))` — so the recipe takes the weaver's other word,
  **draft**.)
- **embroidery** — the deliberate finishing stitched onto the cloth *by hand*,
  where consensus is unavoidable (its own section below). (We pointedly avoid
  calling it a *seam*: `Seam` is already the core contract type.)

![One loom, three layers: the draft (recipe) is woven into coordination-free cloth by the warp, weft and shuttle, then the embroidery — consensus — is stitched on top by hand.](images/warp/loom.svg)

*(How does a function actually reach other peers, when a phone, a server, and a
browser can't share compiled code? You ship a **name**, not the code — which, in
the near-term form, means **every peer runs the same build** (true code mobility
across versions is the wasm-kernel fantasy, with real iOS limits). The deeper
machinery, including the **bobbin/creel** code cache that ships real kernels lazily,
lives in [`warp-execution.md`](warp-execution.md).)*

## The reduction to simplicity

This is the heart of the vision, and the part the documentation must *dramatize*.

A distributed compute grid is, on paper, terrifying: membership protocols,
failure detectors, gossip, anti-entropy, convergent replicated state, load
balancing, consensus, exactly-once delivery. A reader's shoulders go up just
reading the list.

The dream is to watch that entire list **collapse**. Each item is real, but each
one is already solved inside kuilt and already invisible — so the surface that
remains is:

```kotlin
warp.shuttle(corpus) { score(query, it) }.weave()
```

That collapse *is* the product. The documentation should walk the reader down the
mountain: here is everything a grid needs (the scary list) → here is where each
piece already lives (the table) → here is what's left for you to type (three
lines). The feeling we are selling is **relief**: "oh — I don't have to think
about any of that."

![The descent: a terrifying list of everything a distributed grid needs collapses, tier by tier, into the pieces kuilt already ships, and finally into the three lines of code you actually type. The collapse is the product — relief.](images/warp/descent.svg)

## Embroidery: the one place you stitch by hand

There is exactly one place the simplicity is *allowed* to leak, and it leaks for a
deep reason, not a missing feature. The woven cloth is cheap and mechanical; the
**embroidery** is the deliberate finishing you work onto it by hand, with a
different tool, where the value concentrates.

Some questions can be answered with no coordination at all: *how many?*, *what's
the running total?*, *merge everyone's partial rankings*. These only ever grow —
add more data, the answer only gets more complete — so every peer can compute them
independently and they always converge. The **CALM theorem** is the precise
statement of this: computations expressible in monotone logic have
coordination-free distributed implementations, and *only* those do.

The other kind of question forces agreement: *who is the single winner?*,
*assign this task to exactly one peer*, *commit — final answer, no take-backs*.
A late arrival can change a winner, so the peers must stop and agree. CALM says
this isn't pessimism or a weak implementation — it is a hard boundary. Crossing it
costs a round of consensus, every time, for everyone.

The beautiful move is to make that boundary the cloth's **embroidery** — the one
part worked by hand, on top, where the type itself flips from woven to stitched:

```kotlin
val ranked : CoordinationFree<Ranking<DocId>> =          // woven cloth — zero coordination
    warp.shuttle(corpus) { score(query, it) }.weave()

val winner : Coordinated<DocId> = ranked.top()           // argmax is non-monotone → type flips
val chosen : DocId              = winner.commit(raft)     // the ONLY consensus — the hand-stitch
```

`commit` is the needle going in: `embroider { top().commit(raft) }` reads as one
deliberate stitch, and it is the only place a Raft round is ever spent.

Two properties make this lovely rather than burdensome:

1. **You never write a proof.** `shuttle` and `weave` are coordination-free *by
   construction* — they are the library's vetted monotone combinators. The CALM
   theory isn't on your screen; it retreated into the combinators where it can't be
   gotten wrong. (Earlier in the dream we imagined forcing the user to *prove*
   monotonicity in the type system — provably correct, and unusable. The right
   place for the theorem is inside the library, not on the caller's keyboard.)
2. **You can't spend consensus by accident.** The only way to get a single agreed
   answer is through `Coordinated`, and `.commit(raft)` is the only door out of it.
   The cost is grep-able, reviewable, and impossible to hide. The compiler quietly
   enforces the one rule that matters: *don't coordinate unless the question
   actually requires it.*

So the surface tells the truth: everything cheap looks local; the one expensive
thing looks like exactly one expensive line.

And not every stitch is a *full* consensus round, either. For the common case of a
shared **random** choice — who goes first, breaking a tie — kuilt's dealing module
already has a lighter commit-reveal agreement (no trusted dealer) that costs far
less than electing a leader through Raft. The embroidery has more than one needle;
spend the cheapest one the question allows.

## Horizons — the fantasy, last

Everything above is the walk. Past it the dream keeps going, mapped by the
[deeper-waters index](warp-deeper.md):

- **Live code mobility** ([execution](warp-execution.md)). Ship a sandboxed **WASM
  kernel** as the method itself, so the same bytes run on a phone, a server, and a
  browser — with *compiler nodes* tiering it from interpreted to native.
- **It plans and watches itself** ([planning](warp-planning.md),
  [observability](warp-observability.md)). The draft is a query plan the grid
  optimizes for *coordination* rather than IO; logs, metrics, and traces fall out
  of the same CRDTs — a trace DAG you *infer* from causality instead of instrumenting.
- **Federated & parallel ML** ([AI & modelling](warp-ml.md)). A brokerless,
  multiplatform substrate for federated learning, distributed inference,
  hyperparameter search, and sharded retrieval — the aggregation-shaped ML the CALM
  boundary blesses.

These are dessert. The main course is the three lines.

## What is real, and what is a dream

- **Real, and the only thing we might build:** the spike in #680 — wire the
  existing pieces into a minimal `Warp`, run one `shuttle`/`weave` over the
  simulation harness, and write down where the CALM boundary actually bites.
  Throwaway by default. Never wired into the default target set or the public API
  without a separate, deliberate decision.
- **A dream:** everything else here and in the companion — the polished
  `warp`/`weft`/`shuttle`/`draft`/`embroidery` surface, the
  `CoordinationFree`/`Coordinated` types, wasm-kernel code mobility, the
  descent-shaped documentation. It exists to give the spike a north star and to be
  enjoyed, not to be scheduled.

The hero example here is search-and-rank because it contains both halves (a
monotone weave and a non-monotone winner); any embarrassingly-parallel +
reduce + decide workload tells the same story and is freely swappable.

## The walk is part of the dream

If `:kuilt-warp` is ever real, **its documentation should take the reader on the
exact walk this page just took** — that is itself a design goal, not a nicety.
The product's landing page, README, and guide should descend the same mountain, in
the same order:

1. **One idea, in plain language** — "what if running your code across a roomful of
   machines felt like running it on one?" A curious non-engineer understands the
   first screen. (This is already kuilt's house rule: accessible first, technical
   depth only deeper.)
2. **Recognition** — it's mostly already built; here is where each piece lives.
3. **The reduction** — watch the terrifying list collapse to three lines. The
   feeling is *relief*.
4. **The honest seam** — the one place (embroidery / consensus) the simplicity is
   allowed to leak, and *why* the mathematics makes it so.
5. **The fantasy, last** — code mobility, the WASM trick — dessert, not the main.

The diagrams that carry the walk (`descent`, `loom`, `recognition-map`) live here;
the companion carries the deeper ones (`on-the-wire`, `code-mobility`, `bobbins`,
`query-planner`). The reason to capture this now, while it is only a dream, is that
an AI- or contributor-authored rewrite tends to re-derive the docs from the
*technical* baseline — leading with `CoordinationFree`, lattices, and Raft — and
the walk is lost. **The walk is the product as much as the API is.** Preserve the
descent.

## The precedent

If we ever did build it, we would be walking a path **Lasp** (Meiklejohn & Van
Roy, PPDP 2015) already mapped: CRDTs as the computational substrate, monotone
programs proven equivalent to a single centralized execution, no consensus on the
happy path. `:kuilt-warp` would be Lasp's idea wearing kuilt's clothes — and
standing on machinery kuilt already had all along.
