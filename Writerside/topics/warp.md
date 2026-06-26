# Warp

> **Experimental.** The scheduler described here is real and shipping, but pre-1.0 —
> its API can change and it sits outside kuilt's stability promise. Everything past
> "the dream" below is speculative research (epic #665, spike #680), with no
> commitment to build. Treat this whole area as a preview, not a foundation.

Imagine a roomful of devices — phones, laptops, whatever is connected — with a pile
of work to get through. You want them to share the pile: each device grabs a job,
does it, and puts the answer back. When they're done, all the answers are in one
place, **no job was done twice**, and **nobody was in charge** — there was no central
server handing out assignments.

That's warp in one sentence. And the surprising part is that kuilt already has every
piece needed to do it.

## The pieces already ship

Nothing here had to be invented — warp just names a combination of things kuilt
already provides:

- A **shared to-do list** every device can add to and read, which merges cleanly when
  two devices reconnect after a split — that's an `ORSet` from [Replicated Data](crdt-overview.md).
- An **answers board** that keeps one answer per job even if a job ran twice — an
  `ORMap`, where a duplicate just overwrites itself.
- A way to decide **who does which job** without asking anyone — *consistent hashing*:
  arrange the connected devices in a ring, and each job has one natural owner (the
  nearest device clockwise). No vote, no lock, no round-trip.
- The **list of who's connected**, kept fresh by [Consensus](raft.md) or the room
  roster.
- **Cover for a device that drops out** — kuilt's liveness detection notices, and the
  next device on the ring picks up the dropped jobs.

## How you'd use it

You give each device an `executor` — the function that actually does a job — and then
add jobs to the shared list. Each job runs on exactly its owner; the answers converge
everywhere:

<!-- condensed from kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpNodeTest.kt#resultsBoardConvergesAcrossAllPeers -->

```kotlin
val nodeA = WarpNode(
    selfId = seamA.selfId,
    seam = seamA,
    rosterFlow = seamA.rosterSnapshot(),   // who's connected
    scope = backgroundScope,
    quilterConfig = quilterConfig,
    clock = clock,
    executor = { taskId -> "result-${taskId.value}" },   // the work each job does
)
// ...a second node on another peer, wired the same way...

val tasks = (1..8).map { TaskId("conv-task-$it") }
tasks.forEach { nodeA.enqueue(it) }   // add work to the shared list

advanceUntilIdle()

// Each task ran on exactly one peer — its owner on the ring.
// None lost, none run twice; the results board converges on every node.
assertEquals(tasks.toSet(), nodeA.results.taskIds)
assertEquals(tasks.toSet(), nodeB.results.taskIds)
```

The whole scheduler is just those parts tied together: a ring over the connected
peers, a work queue, and a results board as the safety net.

## The honest seam

The "no central boss" trick holds as long as the set of connected devices is stable.
When a device joins or leaves, the jobs whose owner changed get reassigned — that
costs one membership-change event per affected job, not a negotiation per job. At low
churn this is much cheaper than coordinating every job; at very high churn the cost
rises, and the answers board is what keeps the worst case to "a job ran twice" rather
than "a job got the wrong answer." The
[spike measurements](https://github.com/tractat-us/kuilt/blob/main/docs/warp-spike-results.md)
checked this boundary directly: roughly zero duplicates while membership is steady,
and never a wrong result.

By default warp also *trims* those churn-window double-runs before they happen. In the brief
moment right after a device joins or leaves, two devices can briefly disagree about who owns a
job and both start it. So before running a job a device first quietly *calls dibs* — a small
note that rides along with traffic already flowing — and, during that unsettled moment, waits a
beat and runs the job only if it's the agreed owner. Two devices that would have raced now have
one step aside. It costs nothing in the steady state — there's no note to wait on and no pause —
and the answers board is still the final safety net for anything that slips through. (Prefer the
simplest behaviour? Selecting `ClaimStrategy.Ring` turns the dibs step off and leans on the
answers board alone.)

## Choosing how to run a job

Every job is more than data — it's also a plan for how that data should flow before
the answer is committed. Warp makes that plan something you can hold in your hand,
inspect, and rewrite before anything moves.

### A Draft is the plan

`Warp.shuttle(opId)` returns a `Draft` — an immutable description of a pipeline. You
chain `.map()`, `.filter()`, and `.embroider()` on it and get a record of what should
happen, with nothing executed yet:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleShuttle -->

```kotlin
val draft: Draft<ByteArray> = Warp.shuttle(OpId("docs"))
    .map(OpId("score"))
    .filter(OpId("above-threshold"))
    .embroider(OpId("rank"))

check(draft.stages.size == 4)
check(draft.isMonotone.not())          // has an Embroider stage
check(draft.embroidery?.opId == OpId("rank"))
```

`isMonotone` tells you at a glance whether any consensus step is even needed.
`embroidery` is the single coordination point — you can locate it, inspect it, or defer
it without touching any data.

### Making the plan better

Three pure rewrites can improve a draft without changing what it computes:

- **Defer the consensus step.** Push the `embroider` as far right as possible so the
  agreement covers the smallest set of already-filtered elements.
- **Push filters early.** Move filter stages ahead of map stages so less data flows into
  the heavier transforms. This assumes each filter operates on the source element, not on
  a map's derived value — the current model carries only symbolic names, not type
  annotations, so filter-before-map is a modelled assumption rather than a proved
  dependency check.
- **Fuse adjacent stages.** Collapse a run of consecutive maps (or filters) into one,
  so the runtime applies them in a single pass.

`optimize()` applies all three to a fixpoint and returns a structurally equivalent draft:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleOptimize -->

```kotlin
val optimized = unoptimized.optimize()
check(optimized.stages.last() is DraftStage.Embroider)   // embroider deferred last
check(unoptimized.isEquivalentTo(optimized))              // same convergent result
```

### What it costs

`coordinationCost(stats)` scores a draft with two numbers: how many consensus rounds it
needs (`rounds`) and how many elements will cross that boundary (`coordinatedVolume`). In
the common case of a single `embroider` stage, `rounds` is always 1 — the planner's real
win is cutting `coordinatedVolume` by deferring the embroider past selective filters:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleCoordinationCost -->

```kotlin
// Unplanned: embroider before filter → consensus sees ~1000 docs
val unplannedCost = unplanned.coordinationCost(stats)
check(unplannedCost.coordinatedVolume >= 900L)

// Planned: embroider deferred → consensus sees only ~50 docs
val planned = unplanned.plan(stats)
val plannedCost = planned.coordinationCost(stats)
check(plannedCost.coordinatedVolume < 100L)
check(plannedCost < unplannedCost)
```

The cardinality estimates come from `WarpStats` — a CRDT map of per-source
HyperLogLog sketches. Peers gossip these sketches on the same anti-entropy as everything
else; each peer plans locally from the converged value, no round-trip needed.

### Results that converge

A monotone pipeline never "finishes" — it **converges**, refining as contributions arrive
from distributed peers. `IncrementalResult` holds the running join: contribute a lattice
delta and the state grows; it can never shrink. `awaitThreshold` suspends until a monotone
predicate is first satisfied, then returns a snapshot that stays valid permanently:

```kotlin
val result = IncrementalResult(GCounter.ZERO)
result.contribute(GCounter.of(alice to 3L))
result.contribute(GCounter.of(bob to 2L))
// result.state.value.value == 5

// In a coroutine: suspends until the predicate crosses, returns once, stays valid.
val crossed = result.awaitThreshold { it.value >= 5L }
```

`ConvergentExecution` wires a `Draft` to an `IncrementalResult` and processes submitted
deltas asynchronously. The scope is required — production wires a service scope; tests wire
`backgroundScope` to share the virtual clock.

## The dream

The scheduler above is the real, measured first step. The design docs below explore
where it could go — all speculative, none scheduled:

- **[The vision](https://github.com/tractat-us/kuilt/blob/main/docs/warp-vision.md)**
  — the full walk: why a roomful of machines could feel like one computer.
- **[Deeper waters](https://github.com/tractat-us/kuilt/blob/main/docs/warp-deeper.md)**
  — an index into the machinery below the vision.
- **[Execution engine](https://github.com/tractat-us/kuilt/blob/main/docs/warp-execution.md)**
  — how work travels as descriptors, and the caching that makes it cheap.
- **[Query planning](https://github.com/tractat-us/kuilt/blob/main/docs/warp-planning.md)**
  — choosing *how* to run a job to minimise coordination.
- **[AI & modelling](https://github.com/tractat-us/kuilt/blob/main/docs/warp-ml.md)**
  — training a shared model across many devices without gathering their data.
- **[Observability](https://github.com/tractat-us/kuilt/blob/main/docs/warp-observability.md)**
  — reading traces straight off the causality the data already carries (the *shipped*,
  non-speculative slice of this became [Observability](observability.md)).
- **[Epic slices](https://github.com/tractat-us/kuilt/blob/main/docs/warp-slices.md)**
  — how the work would decompose, and which single slice is real today.
- **[Foundation note](https://github.com/tractat-us/kuilt/blob/main/docs/warp-foundation.md)**
  — why `:kuilt-warp` stays experimental and outside the stable surface.

## The fantasy, last

The furthest-out idea: jobs that are not just *data* but *code* — a function that
travels to whichever device is free, runs there, and sends its answer back. That's
code mobility, and it's a long way off. The honest first step toward it is exactly
what ships today: a correct, measured way to spread work across a mesh, built from
parts kuilt already had.
