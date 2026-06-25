# `:kuilt-warp` — foundation design note

> Part of the [`:kuilt-warp`](warp-vision.md) epic (#809). See
> [warp slices](warp-slices.md) for the full decomposition and
> [warp spike results](warp-spike-results.md) for the measurements
> that justify this architecture.

## The core idea

Distribute a batch of tasks across a connected mesh: each peer claims and executes the tasks
that hash to it, results land in a shared map, and a dedup backstop absorbs duplicate
executions if two peers race to claim the same task.

No central scheduler. No per-task vote. The ring decides.

## Architecture

### The ring (`TaskRing`)

Peer IDs are sorted onto a consistent-hash ring. For a given task ID, the natural owner is
the nearest peer clockwise on the ring — a pure local computation, zero messages.

When that peer fails, `:kuilt-liveness`'s `PartitionDetector` fires and the next peer
clockwise takes over. Failover is one ring-lookup from the perspective of any surviving peer.

**Ring source — pluggable, two options:**

| Source | Type | When to use |
|--------|------|-------------|
| Raft dynamic membership (`:kuilt-raft`) | Consensus-backed, consistent across all peers | Default; the measured sweet spot (see spike results) |
| Session room roster (`:kuilt-session`) | Gossip-propagated, cheaper, slightly stale | Groups with < 5% churn per task batch |

The raft ring gives every peer the same view of membership at the cost of a membership-change
round-trip. The session roster is available immediately from the `Room` but may lag by one
gossip round during rapid churn. The `TaskRing` interface abstracts the source; callers choose
at construction.

### The work queue (`WorkQueue`)

An `ORSet<TaskId>` from `:kuilt-crdt`, replicated live via a `Quilter` from `:kuilt-quilter`.

`ORSet` is the right structure: concurrent adds from multiple peers merge correctly, and
removing a completed task (tombstoning) is safe once the result is confirmed. Tasks added
during a partition are preserved and merged on reconnect — no task is ever silently lost.

### The results map (`Results`)

An `ORMap<TaskId, LWWRegister<Result>>` from `:kuilt-crdt`, also replicated via `Quilter`.

Last-write-wins per task: if two peers race to claim the same task (a duplicate execution),
one result overwrites the other. The `ORMap` ensures every task has exactly one result
once the state converges. Duplicate *execution* is tolerated; duplicate *results* are not.

### Load balancing

`BoundedCounter` from `:kuilt-crdt` is a distributed equalizer. Each peer holds a quota
proportional to the number of tasks; claiming a task spends one unit. The counter's
diffusive rebalancing (the equalizer algorithm) redistributes quota across peers over time,
keeping per-peer load approximately equal without a central dispatcher.

### The `CoordinationFree` / `Coordinated` type seam

The ring, queue, and results map together form the `CoordinationFree` path: tasks flow and
results accumulate with no consensus required as long as membership is stable. The
`Coordinated` path — Raft-backed proposals for tasks that are not idempotent or that require
strict exactly-once — is a later slice (B in the slice taxonomy).

The type boundary makes the trade-off explicit at the call site: callers opt into coordination
for the tasks that need it; everything else stays on the fast path.

### The intent-register layer (reducing window duplicates)

The ring eliminates steady-state duplicates, but during a membership change two peers can
briefly disagree about who owns a task and both run it. The `Results` board still converges
to one answer — the wasted run is just thrown away. `ClaimStrategy.RingWithIntent` (the
default) trims that waste: before running an owned task a peer *announces* its claim into a
small shared register (a grow-only set of claimants per task), and during the brief
disagreement window it waits a moment and runs the task only if it is the agreed claimant.
The announcement is free — it rides the replication traffic already flowing — and the wait is
paid only inside that window, so the common path keeps its zero-latency, zero-coordination
behaviour. A claim that is won but never finishes (the winner died or stalled) lapses after a
lease, so the net can only ever cost one duplicate, never a lost task. Choose
`ClaimStrategy.Ring` to opt out. See `docs/warp-spike-results.md` for the duplicate-rate
measurements that motivated this.

## Duplicate rate — the go/no-go number

The [spike](warp-spike-results.md) measured the ORSet-queue + ORMap-dedup architecture
directly. At stable membership and low partition rate:

- **2 peers, 0% loss:** ~5% duplicate execution rate (1 dup per 20 tasks)
- **4 peers, 0% loss:** ~17% duplicate execution rate
- **8 peers, high partition:** ~25–29%

For embarrassingly-parallel tasks (idempotent map operations, scoring, compression) these
rates mean warp is ~3–5× cheaper than per-task consensus and the dedup backstop absorbs the
waste. For tasks that are expensive to re-run or not idempotent, the `Coordinated` path
(slice B) adds Raft-backed exactly-once.

## What this module does NOT include

`:kuilt-warp` stays out of the `:kuilt-bom` — it is experimental and its API is expected to
move. It depends on `:kuilt-core`, `:kuilt-crdt`, `:kuilt-session`, `:kuilt-liveness`, and
`:kuilt-raft`, all of which are stable and published.

Code mobility (tasks as serialised functions), query planning, and federated compute are
downstream of this foundation — see the [slice taxonomy](warp-slices.md) for the full map.
