# Partial-mesh gossip — design

> Status: **draft design**, under review. Captures the partial-mesh design agreed
> during brainstorming on 2026-06-22. Implementation is phased (below) and not yet
> started. Part of the performance epic.

## What this is, in plain terms

Today, when a group of devices shares data, **every device connects to every
other device.** With a handful of devices that's fine. With dozens, the number of
connections — and the number of copies of each update flying around — grows with
the *square* of the group size. The scaling measurements bear this out: a single
update can fan out into hundreds of messages once the group passes ~10 members.

This design lets each device connect to only a **handful** of others and still
have every update reach everyone, by **relaying** updates device-to-device — the
way news spreads through a crowd rather than everyone shouting to everyone. The
result: the work per update grows roughly *in line with* the group size instead of
its square, so larger groups stay practical.

It applies to the **shared-data** path (the CRDT replication layer). The
**agreement** path (consensus) is deliberately left alone — it keeps its small,
fully-connected core, because agreement needs every participant reliably in the
loop and that core is tiny anyway.

## Why (the measured problem)

The `:kuilt-scale` harness measured a full mesh (`MeshSeam`): broadcast fan-out
and per-event message counts grow as **O(N²)** in the peer count (e.g. CRDT-zoo
sends grew ~linearly per peer × N peers; BoundedCounter's reactive rebalancing
grew ~10× from N=3→10 before #643). A full mesh also costs `N·(N-1)/2`
connections and `N-1` sockets per peer. For the replicated-data path this caps
practical group size well below where the consistency model would otherwise allow.

Consensus stays on the complete voter core: a Raft cluster needs reliable,
ordered delivery to every voter, the voter set is small (3–7), and `:kuilt-cluster`
already pairs that complete core with a sparse learner periphery. Partial mesh is
the answer for *gossip-tolerant* replication, not for consensus.

## Architecture

```
   Quilter (CRDT replication)        gossip-mode: no per-delta acks
   ─────────────────────────
   GossipSeam : Seam                 broadcast = Plumtree ; sendTo = flood-with-filter
   ─────────────────────────
   Plumtree           |  HyParView view-management
   (broadcast tree)   |  (active / passive views + shuffle)
   GRAFT/PRUNE/IHAVE  |
   ─────────────────────────
   :kuilt-liveness  (PartitionDetector)   ← the failure-detection half, REUSED
   ─────────────────────────
   :kuilt-core  (Seam / Connection / Swatch)
```

New module **`:kuilt-gossip`** → depends on `:kuilt-core` + `:kuilt-liveness`;
exposes a `GossipSeam : Seam` so consumers (Quilter) need no structural change —
only the reliability-mode adjustment in §5.

### 1. Target — replicated-data layer only

The gossip mesh serves Quilter/CRDTs, where eventual delivery is acceptable
because anti-entropy heals any gaps. Raft is out of scope and stays complete-graph.

### 2. Dissemination — Plumtree

Plumtree (the "epidemic broadcast trees" algorithm) disseminates each broadcast
along a **self-pruning spanning tree**: the message is *eager-pushed* to a peer's
eager-set and announced as a lazy `IHAVE` (message id only) to the rest. On
receiving a new message a peer delivers it, eager-forwards to its other eager
peers, and lazy-announces to the rest; a duplicate arriving eagerly triggers a
`PRUNE` (demote that link to lazy). A peer that sees an `IHAVE` for a message it
hasn't received starts a timer and, on expiry, `GRAFT`s (requests the payload and
promotes the link to eager) — this is also how the tree **self-heals** when a
branch dies. Steady state is ~O(N) eager messages per broadcast; the lazy layer is
cheap (ids only) and provides redundancy/recovery.

A small **gossip header** (origin id + monotonic sequence) wraps each app `Swatch`
for message identity; a bounded **seen-set** drops duplicates; a TTL bounds loops.

### 3. Membership — HyParView views on top of `:kuilt-liveness`

Plumtree is defined relative to a peer-sampling service that supplies each peer's
neighbor set and up/down events. HyParView is the canonical such service, but it
bundles **two** concerns that kuilt keeps separate:

- **Failure detection** — "is this neighbor alive?" kuilt **already owns this** in
  `:kuilt-liveness` (`PartitionDetector` → `PartitionEvent`), the single failure
  detector shared across session/game.
- **View management** — a small **active view** (the gossip neighbor set, ~log N)
  + a larger **passive view**, kept fresh by periodic **shuffle**, with promotion
  from passive → active when an active neighbor drops.

So kuilt builds **only the view-management half** and **consumes** `:kuilt-liveness`
for the neighbor-down signal — HyParView does *not* re-implement keepalive, and
liveness is *not* replaced. This keeps one failure detector for the whole library
(no duplicate heartbeating) and is the decomposition that makes the integration
clean rather than bolted-on.

**Integration seam:** `:kuilt-liveness` must monitor the *dynamic active-view
subset* (the peers you're actually connected to), not the full roster — you are
deliberately not connected to everyone. If `HeartbeatPartitionDetector` currently
assumes the roster/Seam peer set, generalising it to "watch this changing set of
peers" is the one prerequisite touch.

### 4. Unicast (`sendTo`) — flood-with-filter

Unstructured gossip overlays broadcast well but route poorly (no structure for
key-based routing). After §5 removes high-volume acks, the remaining `sendTo`
traffic is **rare control messages** (e.g. BoundedCounter targeted borrow, #643).
These use **flood-with-filter**: a dest-tagged message rides the Plumtree
machinery; only the target delivers it to the app, everyone else just relays. The
HyParView overlay is connected, so it always reaches the target. Cost is O(N) per
unicast — acceptable for rare events, and it adds zero new mechanism. A proper
next-hop routing layer is a possible future optimization, explicitly **not** in
scope (it routes worst over a churny unstructured overlay).

### 5. Reliability — anti-entropy-primary

A gossip substrate **cannot be fully transparent to Quilter.** Today Quilter,
after broadcasting a delta, has every peer `sendTo` an ack back to the origin —
which on a gossip mesh would be (N-1) acks × O(N) flood = **O(N²)**, silently
re-creating the blowup the mesh removes.

The fix is the natural one for an eventually-consistent mesh: on a `GossipSeam`,
Quilter **drops per-delta unicast acks**; reliability and stability come instead
from **Plumtree's in-tree delivery + `GRAFT` recovery** and **Quilter's existing
anti-entropy digests** (which already drive reconciliation and GC). This is what
delivers true end-to-end O(N) — the Plumtree tree alone does not.

Mechanism: a **Seam capability flag** (e.g. `Seam` reports whether it is a
broadcast-gossip substrate) that Quilter keys off to select anti-entropy-primary
mode. Quilter's public API is unchanged; only its internal reliability strategy
adapts to the substrate.

> **Open risk under review:** this hinges on Quilter's anti-entropy alone
> providing every guarantee the acks currently provide (delta GC / causal
> stability / BoundedCounter transfer confirmation). The design review must
> confirm what acks are load-bearing for before this is locked.

## Module & contract

- **`:kuilt-gossip`** (all targets): `GossipSeam : Seam`, the membership view
  manager, the Plumtree engine. Depends on `:kuilt-core` + `:kuilt-liveness`.
- `GossipSeam` honors the `Seam` contract: `peers` = all logical members (not just
  neighbors), `broadcast` = Plumtree, `sendTo` = flood-with-filter, `incoming`
  single-collection (ADR-034), `availability` reports usability.
- All timers (lazy-push, shuffle, GRAFT) take an **injected dispatcher/scope**
  (required, no real-clock default) per the repo's time-is-a-dependency rule;
  any randomness (neighbor selection, shuffle) uses an injected seeded RNG.

## Implementation phases

Each phase is its own PR, validated on the `:kuilt-scale` harness (the
topology-pluggable mesh builder was built for exactly this):

- **P1 — Membership.** HyParView active/passive views + shuffle over
  `:kuilt-liveness`; generalise liveness to watch the dynamic active-view subset.
  Output: a peer-sampling/membership-view service.
- **P2 — Plumtree.** Eager/lazy broadcast (GRAFT/PRUNE/IHAVE, lazy timers) over
  the active view, with the gossip header + seen-set.
- **P3 — GossipSeam.** Wrap P1+P2 as a `Seam`; pass `SeamConformanceSuite`;
  measure O(N) broadcast vs the full-mesh baseline at higher N on the harness.
- **P4 — Quilter gossip-mode.** Seam capability flag → drop per-delta acks; prove
  end-to-end O(N) CRDT replication on the harness; confirm GC/stability intact.

## Out of scope / deferred

- Next-hop unicast routing over the overlay (flood-with-filter suffices for now).
- Demand-weighted or structured (DHT) overlays.
- Gossip for the consensus path (Raft stays complete-graph).

## Open questions (for review)

1. What exactly are Quilter's per-delta acks load-bearing for, and does
   anti-entropy alone preserve those guarantees? (§5 — highest risk.)
2. Does `:kuilt-liveness` already expose per-peer down events and dynamic-subset
   monitoring, or how large is that generalisation? (§3.)
3. Is full HyParView (active+passive+shuffle) warranted at the tens–low-hundreds
   target scale, or does a roster-derived k-regular view suffice? (§3.)
4. `peers` semantics on a partial mesh: does any Quilter per-peer logic assume
   direct reachability? (§Module.)
