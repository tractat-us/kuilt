# Module kuilt-warp

Spread a pile of work across whoever is connected — with nobody doing the same job twice, and
no central boss telling anyone what to do.

That is warp in one sentence. A group of devices shares a task list. Each one takes a task,
does it, and puts the result back. When everyone is done, all the results are in one place
and no task was run twice — even though nobody coordinated, and even if the network was patchy.

## The pieces are already here

Nothing in that description needs to be invented. kuilt already ships everything warp is made of:

- A **task list** that every peer can add to and read, and that merges correctly when two peers
  reconnect after a split — that is an `ORSet` from `:kuilt-crdt`, an append-only set that
  handles concurrent adds without losing anything.
- A **results board** that deduplicates — that is an `ORMap<TaskId, LWWRegister<Result>>` from
  the same module; the last write wins per task, so a duplicate execution just overwrites itself.
- A way to decide **who takes which task** without asking everyone — consistent hashing over the
  peer roster. Given a sorted ring of peer IDs and a task ID, one peer is the natural owner: the
  nearest peer clockwise on the ring. No vote, no lock, no round-trip.
- The **peer roster** itself, kept fresh by `:kuilt-raft` (dynamic Raft membership — the measured
  sweet spot; see `docs/warp-spike-results.md`) or `:kuilt-session` (the room roster, cheaper
  for groups that change rarely).
- **Failover** when the natural owner goes quiet — `:kuilt-liveness`'s `PartitionDetector` fires,
  and the next peer clockwise on the ring picks up the task.
- **Load balancing** across that ring — `BoundedCounter` from `:kuilt-crdt` is a distributed
  equalizer; each peer spends one quota unit per claim and the counter's diffusive rebalancing
  keeps the load even without central scheduling.

## What warp adds

The scheduler collapses to three things built from those primitives:

1. A **consistent-hash ring** over the current peer roster (the `TaskRing`).
2. A **work queue** (`ORSet<TaskId>`) replicated live via `:kuilt-quilter`.
3. A **results map** (`ORMap<TaskId, LWWRegister<Result>>`) as the dedup backstop.

A `WarpNode` ties them together: watch the queue, claim tasks that hash to you, write results.
That is the entire scheduler — the machinery already existed, warp names the combination.

## Two paths for every task

Not every task is idempotent. A resize is fine to run twice; a payment is not. Warp makes
the distinction explicit at the type level before any task ever runs.

**Coordination-free** tasks live in a join-semilattice — their results merge correctly even if
two peers do the same work concurrently. Wrap the value in `CoordinationFree` and hand it to
the ring. `embroider` merges two contributions; `zip` pairs two `CoordinationFree` snapshots
into one; `joinAll` folds a list of contributions down to a single result. The ring owner
executes directly; duplicate executions are silently absorbed by the `Results` ORMap.

```kotlin
val score: CoordinationFree<PNCounter> = CoordinationFree(PNCounter.ZERO)
val combined = peerA.embroider(peerB)            // componentwise join, always correct
val snapshot = score.zip(CoordinationFree(GCounter.of(replica to 3L))) // pair two states
```

**Coordinated** tasks require a consensus round-trip — they are non-idempotent, have strict
ordering requirements, or must run exactly once globally. Wrap the value in `Coordinated`, call
`enqueue(taskId, CoordinationKind.Coordinated)`, and supply a `raftNode` and
`coordinatedExecutor` to `WarpNode`. The ring owner proposes the task to the Raft cluster; only
the current Raft leader's `WarpNode` fires `coordinatedExecutor` when the entry commits. The
`Results` ORMap backstop absorbs any duplicate results from the brief dual-leader window.

```kotlin
// Opt into the Raft-backed path for a non-idempotent task:
warpNode.enqueue(taskId, CoordinationKind.Coordinated)
// coordinatedExecutor runs exactly once on the Raft leader's WarpNode.
```

The type boundary enforces the choice at compile time: `CoordinationFree` values can flow
through `embroider`/`zip`/`joinAll`; `Coordinated` values cannot. A caller who picks
`Coordinated` is automatically routed to the consensus path — there is no way to
accidentally put a non-idempotent task on the ring path.

## The honest seam

Consistent hashing stays coordination-free as long as the peer roster is stable. When a peer
joins or leaves, every task whose hash lands in the affected arc is reassigned — that
reassignment costs one membership-change event per affected task, not per-task consensus. At
low churn (fewer than five membership changes per task batch, roughly) this is significantly
cheaper than per-task consensus. At very high churn the cost rises toward per-task; the
`ORMap` dedup backstop caps the damage to duplicate execution rather than incorrect results.

The spike results (see `docs/warp-spike-results.md`) measured this boundary directly:
~0 duplicates per task at stable membership on the coordination-free path; the coordinated
path achieves dup-rate=0 under roster churn (measured in B-4/#861).

## Choosing how to run a job — query planning

Every task is more than data. It is also a *plan* for how that data should flow before the
result is committed. Warp makes that plan inspectable before a single byte moves.

A `Draft` is the plan. Call `Warp.shuttle(opId)` and chain `.map()`, `.filter()`, and
`.embroider()` and you have a `Draft<T>` — an immutable record of what should happen, with
nothing executed yet. The `stages` list is the ordered pipeline; `isMonotone` tells you
whether any consensus step is needed at all.

```kotlin
val draft: Draft<ByteArray> = Warp.shuttle(OpId("docs"))
    .map(OpId("score"))
    .filter(OpId("above-threshold"))
    .embroider(OpId("rank"))

check(draft.stages.size == 4)
check(draft.isMonotone.not())          // has an Embroider stage
check(draft.embroidery?.opId == OpId("rank"))
```

### The rewrite rules

Once you have a `Draft`, three pure transformations can improve it — none changes what
the pipeline computes:

1. **`deferEmbroidery`** — float the consensus step (`Embroider`) as late as possible, past
   all free stages, so the agreement covers the smallest possible set.
2. **`pushdownFilters`** — move filter stages ahead of map stages so less data flows into
   the costlier transforms. Safe because both are monotone and commute under CALM. Note:
   this assumes each filter's predicate operates on the source element, not on a map's
   output — no op-dependency metadata exists yet to verify independence at the type level.
   (For multi-embroider components the rewrite is conservative — it returns the component
   unchanged, since filter-before-map is provable only within a single-embroider path;
   `consolidateEmbroideries` handles the cross-embroider structure separately.)
3. **`fuseAdjacent`** — collapse runs of consecutive same-kind free stages (`Map`/`Filter`)
   into a single `FusedMap` or `FusedFilter` that the runtime can apply in one pass.

`optimize()` composes all three to a fixpoint and returns a structurally equivalent `Draft`.
`isEquivalentTo` is the semantic-equivalence predicate that confirms the result is the same:
same source, same embroider, same multiset of free operations.

### From a pipeline to a dependency graph

A real query rarely has just one consensus step. A `Draft` is a dependency graph — each
stage is a node with a set of predecessors — and a linear pipeline is the degenerate case
where every node has exactly one predecessor. Two independent pipelines can be merged into
one `Draft` with `combine`:

```kotlin
val docsQuery = Warp.shuttle(OpId("docs")).map(OpId("score")).embroider(OpId("rank"))
val votesQuery = Warp.shuttle(OpId("votes")).filter(OpId("nonzero")).embroider(OpId("tally"))

// Two independent branches — their embroideries share no ancestor path.
val combined: Draft<Unit> = docsQuery.combine(votesQuery)
check(combined.embroideries.size == 2)
```

The combined draft has two `Embroider` nodes with no edges between them — neither is
the other's ancestor. This independence is structural, not declared.

### Cutting coordination rounds

Here is the key result. When a draft has multiple independent `Embroider` nodes, the
minimum number of consensus rounds is **the depth of the dependency graph**, not the
count of individual agreements. Two embroideries that are independent can share a single
Raft round-trip; only a sequential dependency (one embroidery whose input depends on
another's output) forces a second round.

`consolidateEmbroideries()` makes this concrete: it groups `Embroider` nodes at the same
dependency level and fuses each group into a single `BatchedEmbroider` — one Raft proposal,
one round, regardless of how many agreements it carries.

`plan(stats)` applies all the rewrite rules and then consolidates:

```kotlin
// Representative query: 3 independent embroideries (level 0) + 1 sequential (level 1).
val branchA = Warp.shuttle(OpId("src.a")).embroider(OpId("emb.a"))
val branchB = Warp.shuttle(OpId("src.b")).embroider(OpId("emb.b"))
val branchC = Warp.shuttle(OpId("src.c")).embroider(OpId("emb.c"))
    .map(OpId("map.m")).embroider(OpId("emb.d"))   // emb.d depends on emb.c

val unplanned: Draft<Unit> = branchA.combine(branchB).combine(branchC)
val planned: Draft<Unit>   = unplanned.plan(WarpStats.empty())

val stats = WarpStats.empty()
check(unplanned.coordinationCost(stats).rounds == 4)   // one per Embroider node
check(planned.coordinationCost(stats).rounds   == 2)   // DAG depth: level 0 + level 1
```

This is the improvement that E-3 could not demonstrate — E-3 was structurally pinned at
≤ 1 rounds on a single-embroider pipeline. `rounds` is now a real lever.

### The honest tradeoff — coupling and blast radius

Batching K independent agreements into one round is efficient, but it **couples their failure
domains**: if the Raft proposal is rejected (a leader stepdown, a conflict, a node restart),
all K agreements must retry together. `CoordinationCost.coupling` records the maximum batch
size and is the secondary objective in the lexicographic ordering (minimise rounds first,
then coupling, then volume). A future planner might cap batch size explicitly; for now the
caller can inspect `coupling` and split a draft if the retry unit is too large.

### The cost model

`coordinationCost(stats)` scores a `Draft` as a `CoordinationCost` with three fields:

- **`rounds`** — the count of coordinated nodes in the planned DAG. Zero for a fully-monotone
  pipeline; one per dependency level otherwise. A `BatchedEmbroider` counts as one round
  regardless of how many ops it batches.
- **`coupling`** — the maximum batch size across all coordinated nodes: 1 for a lone
  `Embroider`, K for a `BatchedEmbroider` carrying K agreements. The blast-radius term.
- **`coordinatedVolume`** — the estimated number of elements entering all coordinated stages,
  derived from `WarpStats` HyperLogLog sketches.

`CoordinationCost` implements `Comparable`: fewer rounds first, then smaller coupling, then
lower volume. `plan` calls `optimize()` (which includes `consolidateEmbroideries`) to
minimise this cost; `stats` is passed but currently reserved for future stats-aware filter
reordering (e.g. most-selective first).

### Executing a plan

`Draft.executeCoordinated { propose }` walks the coordinated nodes in topological order and
calls `propose` once per node, returning the proposal count. A `BatchedEmbroider` encodes
all its agreements in one payload — one Raft round-trip, not one per op. For fully-monotone
drafts it issues zero proposals and returns immediately.

```kotlin
// Planned: BatchedEmbroider(A,B,C) + Embroider(D) → exactly 2 real Raft proposals.
val rounds = planned.executeCoordinated { payload ->
    raftNode.propose(payload)   // suspends until the proposal commits
}
check(rounds == 2)   // == coordinationCost(planned, stats).rounds
```

The analytical model's `rounds` field matches the execution count exactly — the G5 Raft
simulation test verified this on a real three-node cluster.

### Statistics are a CRDT

The `WarpStats` that feeds the cost model is itself a join-semilattice — a `Map<OpId,
HyperLogLog>` with element-wise max as the join. `observe(source, element)` returns a sparse
`Patch` delta; peers gossip these deltas on the same anti-entropy as the rest of the warp
state. The planner reads estimates locally from the converged value; no round-trip required.

```kotlin
var stats = WarpStats.empty()
for (doc in documents) stats = stats.piece(stats.observe(OpId("source.docs"), doc.id))
val estimate: Long = stats.estimatedCardinality(OpId("source.docs"))  // ~±0.81% error
```

### Execution converges

A monotone pipeline never "finishes" — it *converges*. `IncrementalResult<L>` holds the
running join of all contributions received so far. `contribute(delta)` joins a new lattice
fragment in; the state can only grow. `awaitThreshold { predicate }` is the LVar-style
read: it suspends until the predicate is satisfied and returns a snapshot that is permanently
valid — the lattice cannot fall back below it.

`ConvergentExecution` ties a `Draft` to an `IncrementalResult` and processes submitted
deltas asynchronously on a caller-provided scope. The scope is required with no default —
production wires a service scope; tests wire `backgroundScope` to share the test scheduler.

### Training without sharing data (F2)

Everyone's device helps train a shared model without any private data leaving it. The training
step itself travels as a tiny WebAssembly kernel: it is content-addressed in the `Creel`, fetched
on demand, and run in the sandbox. Each peer runs the same kernel on its own examples and shares
only the resulting weights, which merge through `FedAvg`. See `FedAvgKernelCodec` and
`ReferenceTrainer`.

### Keeping a job on the device that owns the data (pinned execution)

The default ring is *work-stealing*: `hash(taskId)` picks whoever runs a task, so the work can
land on any peer. Some jobs cannot move — a federated-learning step can only run where its private
data lives. **Pinned execution** says "this task belongs to exactly this peer." Set
`TaskDescriptor.pinnedOwner`, or — the common case — call `WarpNode.enqueueLocal`, which pins the
task to the calling node. A pinned task runs only on its owner; no other peer ever runs it; and if
the owner is offline the task simply waits for it to come back rather than re-homing to a survivor.
That last property is what makes the data-local model honest: the work never silently moves to a
peer that does not have the data.

## The further out

Once the ring is working, the same substrate can carry something more ambitious: tasks that
are not just data but *code* — serialised functions that travel to a peer, run there, and
return results. That is code mobility, and it is a long way off. The foundation here — a
correct, measurement-backed distributed scheduler over kuilt's existing primitives — is the
honest first step toward it.

### Tiered compilation (mechanism spike)

A peer that lacks a compiled kernel **interprets** the raw bobbin immediately, and **tiers
up** to a compiled variant once a stronger *compiler node* builds one and gossips it across
the mesh — a JIT smeared across the network. A variant rides the bobbin manifest additively
as a `BobbinMeta(hash, variantOf = VariantKey(source, target, optLevel))`; a node with a
`target` resolves the best variant for its platform per execution and counts
interpreted-vs-compiled executions (`executionsInterpreted` / `executionsCompiled`).

The current spike proves **distribution and swap**, not speedup — its compiler is a
deterministic no-op transform. Genuine optimization (GraalWasm / Kotlin-Wasm / `wasm-opt`)
is a later epic. The iOS ceiling stays *interpret*: Apple forbids executing
externally-delivered machine code, so a compiler node can ship iOS an optimized wasm→wasm
variant but never native code.
