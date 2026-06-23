# `:kuilt-warp` — deeper waters

> **Companion to [`warp-vision.md`](warp-vision.md) — same dream, the machinery.**
> The vision doc is the *walk*: read it first. This page is the depth a reader
> reaches only after the relief — how work actually crosses the fabric, how code
> ships, how the system plans and observes itself, and where it could point in the
> AI space. Still a dream (#665, spike #680); still no commitment to build.

## How the method actually travels

A fair question kills most compute-grid dreams: *how does `score` even get to the
other peers and run there?*

**You never ship the function. You ship a name and its arguments.** kuilt moves
opaque bytes between peers that might be a JVM server, an iPhone (Kotlin/Native),
and a browser (wasmJs). A Kotlin lambda compiled to JVM bytecode cannot execute on
Native or wasm — there is no portable, runtime-shippable representation of Kotlin
code across those targets. So what crosses the fabric is a tiny **task
descriptor** — `{ op: "score", arg: ⟦query⟧, item: docId }` — dropped into the
**`TaskQueue`** (a thin wrapper over an `ORSet`). A peer claims it, looks `"score"`
up in a **local operation registry** that every node populated at startup from the
same compiled binary, and runs *its own copy* on local data. The result merges back
as a CRDT. The function never moved; only its name did.

![How a method crosses the fabric, in five steps: shuttle splits the work into descriptors; a CRDT work-queue replicates them; the equalizer places them; each peer runs its own registered copy; results merge back. On the wire: names and data, never the function.](images/warp/on-the-wire.svg)

That implies one honest constraint, stated up front: **a homogeneous binary with
symbolic dispatch.** Every peer runs the same build; the grid distributes
*decisions about where data is processed*, not the processing code. The
`shuttle { … }` block is therefore sugar for "reference a registered operation" —
a compiler plugin (KSP) could auto-register the ops it sees so it still *reads*
like an ordinary lambda.

## Shipping real code: WASM kernels (the fantasy)

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

## Lazy bobbins: gossiping the code

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

## Compiler nodes & distributed tiered compilation

Compilation is just **another warp op**: `compile(wasmHash, target) → compiledHash`.
A *compiler node* is simply a peer that registered the `compile` op and carries the
heavy toolchain (wasmtime/Cranelift); weaker peers embed only an interpreter and
fetch a *precompiled* bobbin from the creel. It slots straight into the bobbin
model — a bobbin gains **variants**: raw `.wasm` plus per-`(target, opt-level)`
compiled forms, each content-addressed by `hash(wasm) + target`. The grid bootstraps
its own code distribution.

This buys **distributed tiered compilation**: a peer starts *interpreting*
immediately, a compiler node produces the optimized native build in the background,
and the peer tiers up when that bobbin gossips in — a JIT smeared across the mesh.

**Reuse mature toolchains, don't invent one.** Two bootstraps already exist:
**Kotlin/Wasm** lets a kernel be authored in the *same language as the app* and
compiled to a portable `.wasm` bobbin (no separate Rust/C), and **GraalVM**
(GraalWasm to execute wasm on JVM nodes; the Graal compiler / native-image as the
AOT engine) is a ready compiler-node toolchain. The caveat is the familiar one:
Kotlin/Native and Graal native-image both lower through LLVM/AOT and so don't
escape the iOS runtime-native-code ban — they bootstrap *authoring* and the
*non-iOS* tiers, not the iOS ceiling.

**The honest iOS asterisk — do not oversell it.** A compiler node cannot rescue
iOS. The iOS wall isn't "iOS lacks a compiler"; Apple forbids **executing
externally-delivered machine code at all** (not just JIT — downloaded native dylibs
are banned too). So no peer can compile arm64 and ship it to an iPhone to run
natively; iOS's ceiling stays *interpret*. What a compiler node *can* still do for
iOS is ship an **optimized wasm** (wasm→wasm via Binaryen `wasm-opt`) — a leaner
module the interpreter runs faster, but never native. The constraint is policy, not
capability, and that is the one thing the mesh cannot optimize away.

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

## Observability falls out too

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

## Applications: federated & parallel ML

The CALM boundary draws a sharp line through the AI space — and warp lands on the
useful side of it for a whole class of workloads:

- **Federated learning / analytics — the standout.** FedAvg is a count-normalized
  sum of model updates: accumulate `(Σweights, Σcount)` as monotone counters and
  divide at read — that's a *weave*. Brokerless, data stays on-device, no central
  server. kuilt already reaches phones, browsers, and servers, so a peer-symmetric
  *multiplatform* federated substrate is genuinely differentiated (most FL is
  server-orchestrated).
- **Distributed inference / batch scoring** — the search-and-rank hero example *is*
  this when `score` is a model forward pass. Models are big immutable blobs → the
  **bobbin/creel** is model distribution; **compiler nodes** tier the runtime.
- **Hyperparameter / NAS / evolutionary search** — parallel trials (shuttle),
  collect (weave), pick-best (embroidery). Exact fit.
- **Sharded vector search / RAG retrieval** — scatter the query, merge partial
  top-k (a bounded join), aggregate. Ensembles/bagging = averaging models = a merge.

**Where it does *not* fit, and CALM says why:** tightly-coupled **synchronous SGD**
training of one big model wants low-latency all-reduce (HPC fabric), the *opposite*
of eventual consistency — a gossip-CRDT mesh paying consensus every step is the
wrong tool. The line is sharp: aggregation/parallel/serving ML → yes; synchronized
inner-loop training → no.
