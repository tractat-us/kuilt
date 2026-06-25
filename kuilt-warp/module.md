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

## The further out

Once the ring is working, the same substrate can carry something more ambitious: tasks that
are not just data but *code* — serialised functions that travel to a peer, run there, and
return results. That is code mobility, and it is a long way off. The foundation here — a
correct, measurement-backed distributed scheduler over kuilt's existing primitives — is the
honest first step toward it.
