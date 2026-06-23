# `:kuilt-warp` — a vision

> **Status: a dream. Highly speculative, unplanned, deliberately aspirational.**
> This is not a spec and not a commitment. It is a picture of the most beautiful
> thing `:kuilt-warp` could be, written so we can decide whether any of it is
> worth a throwaway spike (#680). Part of the research epic #665. Nothing here
> implies code will be written; if it ever is, only a small experiment.

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
- **bobbin / creel** — *(only in the code-mobility fantasy)* a **bobbin** is one
  shippable code kernel — the wound thread the shuttle draws from; the **creel**
  is the lazily-gossiped cache of them. See "Lazy bobbins" below.

![One loom, three layers: the draft (recipe) is woven into coordination-free cloth by the warp, weft and shuttle, then the embroidery — consensus — is stitched on top by hand.](images/warp/loom.svg)

## How the method actually travels

A fair question kills most compute-grid dreams: *how does `score` even get to the
other peers and run there?* The honest answer shapes everything, so the doc states
it plainly instead of hand-waving "runs on whoever has capacity."

**You never ship the function. You ship a name and its arguments.** kuilt moves
opaque bytes between peers that might be a JVM server, an iPhone (Kotlin/Native),
and a browser (wasmJs). A Kotlin lambda compiled to JVM bytecode cannot execute on
Native or wasm — there is no portable, runtime-shippable representation of Kotlin
code across those targets. So what crosses the fabric is a tiny **task
descriptor** — `{ op: "score", arg: ⟦query⟧, item: docId }` — dropped into the
**`TaskQueue`** (a thin wrapper over an `ORSet`). A peer claims it, looks `"score"`
up in a **local operation registry** that every node populated at startup from the
same compiled binary, and
runs *its own copy* on local data. The result merges back as a CRDT. The function
never moved; only its name did.

![How a method crosses the fabric, in five steps: shuttle splits the work into descriptors; a CRDT work-queue replicates them; the equalizer places them; each peer runs its own registered copy; results merge back. On the wire: names and data, never the function.](images/warp/on-the-wire.svg)

That implies one honest constraint, stated up front: **a homogeneous binary with
symbolic dispatch.** Every peer runs the same build; the grid distributes
*decisions about where data is processed*, not the processing code. The
`shuttle { … }` block is therefore sugar for "reference a registered operation" —
a compiler plugin (KSP) could auto-register the ops it sees so it still *reads*
like an ordinary lambda.

### The fantasy: shipping real code anyway

Named ops can only run code already in the deployed binary. If we ever wanted to
ship *new* computations at runtime — true code mobility — there is exactly one
substrate that works across kuilt's targets, and the surprise is that it isn't
native code:

- **① Named ops — the default.** Ship `(opId, args)`; the code is already
  everywhere. Works on every target. Limit: no new code at runtime.
- **③ WASM kernels — the fantasy.** Ship a compiled `.wasm` blob *as* the method.
  It's the same bytes on every platform; it's a capability **sandbox** (decisive —
  you'd be running code other peers sent you); and the browser runs it natively.
  Under the hood the desktop/server runtimes (wasmtime/wasmer) JIT it to native via
  Cranelift/LLVM, so near-native speed comes for free. The one hard constraint:
  **iOS forbids runtime JIT**, so on iPhone you *interpret* the wasm (wasm3) —
  slower, but real and portable.
- **Not LLVM IR on the wire.** The tempting "ship LLVM bitcode for native speed"
  path is a trap: bitcode isn't portable (Google tried it — PNaCl — and retired it
  in favour of WebAssembly), it needs the JIT iOS bans, and it's unsandboxed. LLVM
  keeps its rightful job — *inside* the wasm runtime, the thing that makes wasm
  fast — not as a distribution format.

The lovely inversion: the part that *sounds* like science fiction (live code
crossing a phone, a server, and a browser at once) rides on the most ordinary
substrate we already half-target — while the path that *sounds* like the
performance win (native/LLVM) is the dead end. Code mobility is a tier, not a
switch: names by default, sandboxed wasm kernels when you genuinely must ship code,
never raw native.

![Code mobility as a ladder: named ops are the default; WASM kernels are the fantasy — same bytes everywhere, sandboxed, browser-native, JIT'd to native via Cranelift where allowed and interpreted on iOS where JIT is banned; shipping LLVM IR on the wire is the trap PNaCl already proved a dead end.](images/warp/code-mobility.svg)

### Lazy bobbins: gossiping the code

Shipping a kernel raises its own question — push every kernel to every peer up
front? No. You let it spread the way everything else here spreads: **eventually**.
A kernel is a **bobbin**, the wound thread the shuttle draws from; a peer can't run
an op until the right bobbin is loaded, so bobbins gossip across the mesh lazily,
on demand. The rack of loaded bobbins is the **creel**.

The structure is exactly the CRDT-cache shape — a **content-addressed store with a
CRDT manifest over the keys**, *keys known, values lazy*:

- **Keys** are content hashes. The op-id in a descriptor *is* `hash(kernel)`. The
  set of known bobbin-hashes is a grow-only `GSet<Hash>` (the zoo's `EphemeralMap`
  gives the cache-with-eviction variant) — cheap to gossip, always converges.
  Every peer agrees which bobbins exist.
- **Values** are the immutable kernel bytes, fetched on first use. Content-
  addressing makes the value-merge *trivially* conflict-free: because the key is
  `hash(value)`, any two peers holding the same key hold byte-identical bytes —
  "merge" is just "same bytes," and each value sits in a one-step lattice
  `Absent ⊏ Present`. You gossip availability and pull on demand.

A peer assigned an op whose bobbin it lacks asks a neighbour, pulls the bytes,
re-hashes to verify, caches, and runs; new peers warm their creel lazily.

One honest line: the key-set converging is **safety** (the CRDT guarantees it); the
bytes being *fetchable* is **liveness** (it does not). Pin each bobbin on ≥ k peers,
like any peer-to-peer content store. The shape has a name — a **Merkle-CRDT**:
CRDT state addressed by hash, advertised eagerly and fetched lazily.

![Lazy bobbins: the op-id is the content-hash of a kernel; the creel is a CRDT GSet of hashes that every peer converges on (keys known), while the kernel bytes are a content-addressed cache each peer holds a subset of and fetches on demand (values lazy). Content-addressing makes every fetch conflict-free; the one caveat is that availability is a liveness property, so pin each bobbin on several peers.](images/warp/bobbins.svg)

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

## Query planning: optimize for coordination, not IO

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

### Observability falls out too

Point the same move at the *running system* and the whole observability stack
appears with no new subsystem — just more of the zoo on the same gossip:

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

**Bolting in OpenTelemetry**, then, is a thin adapter, not a rewrite. OTel
supplies the vocabulary (spans, trace/span ids, metric instruments) and the
dashboards; kuilt supplies a coordination-free, offline-first transport. Three
seams: (1) a CRDT-backed `SpanExporter`/`MetricExporter`/`LogRecordExporter` that
writes into the `Rga`/counter/`Causal` structures instead of POSTing OTLP — and
OTel *cumulative* metric temporality maps cleanly onto monotone CRDT counters;
(2) propagate W3C `traceparent` inside the **task descriptor**, so a trace follows
the work as the shuttle carries it across peers — yet within the mesh the span
links can be *read off the causal DAG* rather than hand-propagated; (3) an edge
collector drains the converged CRDTs to OTLP for Jaeger/Prometheus/etc. You keep
standard instrumentation and backends; you trade real-time delivery for
eventually-consistent, brokerless convergence.

## What is real, and what is a dream

- **Real, and the only thing we might build:** the spike in #680 — wire the
  existing pieces into a minimal `Warp`, run one `shuttle`/`weave` over the
  simulation harness, and write down where the CALM boundary actually bites.
  Throwaway by default. Never wired into the default target set or the public API
  without a separate, deliberate decision.
- **A dream:** everything else on this page — the polished
  `warp`/`weft`/`shuttle`/`draft`/`embroidery` surface, the
  `CoordinationFree`/`Coordinated` types, the wasm-kernel code mobility, the
  three-act documentation. It exists to give the spike a north star and to be
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

The four diagrams on this page (`descent`, `loom`, `on-the-wire`, `code-mobility`)
are the visual spine of that walk; a future guide can reuse them verbatim. The
reason to capture this now, while it is only a dream, is that an AI- or
contributor-authored rewrite tends to re-derive the docs from the *technical*
baseline — leading with `CoordinationFree`, lattices, and Raft — and the walk is
lost. **The walk is the product as much as the API is.** Preserve the descent.

## The precedent

If we ever did build it, we would be walking a path **Lasp** (Meiklejohn & Van
Roy, PPDP 2015) already mapped: CRDTs as the computational substrate, monotone
programs proven equivalent to a single centralized execution, no consensus on the
happy path. `:kuilt-warp` would be Lasp's idea wearing kuilt's clothes — and
standing on machinery kuilt already had all along.
