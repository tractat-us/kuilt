# Partial-mesh gossip — design

> Status: **draft design** for the partial-mesh gossip epic (#652). Revised
> 2026-06-22 after an architect review of the first cut, which found the original
> "drop acks, rely on anti-entropy" plan unsound and reframed where the real
> O(N²) cost lives. Implementation is phased; Phase 1 is the prerequisite and
> where work starts.

## What this is, in plain terms

When a group of devices shares data, the simple approach has **every device
connected to every other.** Fine for a handful; with dozens, the connections —
and the copies of each update flying around — grow with the *square* of the group
size, so large groups stop being practical.

This design lets each device connect to only a **handful** of others and still
have every update reach everyone, the way news spreads through a crowd rather than
everyone shouting to everyone. Two things move between devices: small **updates**
go to your handful of direct neighbours, and, in the background, devices
occasionally compare their **full picture** with a random other device to catch
anything that didn't make it. The first keeps things fast; the second guarantees
everyone eventually agrees.

This applies only to the **shared-data** path (CRDT replication). The
**agreement** path (consensus) is left alone — it keeps its small, fully-connected
core, because agreement needs every participant reliably in the loop and that core
is tiny anyway.

## The reframe: broadcast was never the hard part

The first design assumed the win was a cleverer *broadcast* (a gossip tree) and
that per-delta acks could simply be dropped. An architect review against the code
showed that's backwards:

- **Quilter's delta garbage-collection depends entirely on acks.** A delta is held
  in `pendingDeltas` until acked, and the only thing that prunes it is
  `onAck → recomputeUniversalAck → gcPendingDeltas`, whose watermark is
  `min(ackedThrough)` over the **full membership** (`seam.peers`). There is **no**
  anti-entropy digest fallback for the delta-state zoo — `gossipDelivered` is
  RGA-only. So "drop acks" is an unbounded memory leak, not graceful eventual
  consistency.
- **That full-membership, per-delta ack is the actual O(N²) driver** for the
  delta-state zoo — not the broadcast fan-out. A partial-mesh *broadcast* on its
  own changes nothing about it.

So the real prerequisite is **redesigning delta-GC stability for sparse
membership.** That is Phase 1, and it improves the *current* full mesh too.

## The mechanism: deltas to neighbours, full-state to the rest

The unlock (and the resolution of the review's blocker) is to split replication
into two channels:

1. **Deltas → active neighbours only.** A peer only *owes* deltas to its ~k
   neighbours, so it only needs **acks from those k neighbours** to GC. The GC
   watermark drops from `min over N` to **`min over k`** — the O(N²) term is gone.
2. **Anti-entropy → one random peer per round (the backstop).** Each round a peer
   reconciles with a single randomly-chosen peer by merging state. Because every
   delta-state CRDT is a join-semilattice, merging a full state is idempotent and
   order-independent, so a peer that missed a relayed delta **still converges** on
   the next reconcile. Anti-entropy is the *convergence guarantee*; the delta push
   is just the fast path. Start with full-state transfer (simple, correct, great
   for counters/small sets); a version-vector/digest diff is the later
   optimization for large CRDTs (RGA, big maps).

This is the standard delta-state-CRDT + anti-entropy pairing. It has three nice
consequences:

- **It resolves the `peers` dual-meaning.** GC and delta-push key off the
  **active-neighbour view**; anti-entropy picks from the **full-membership view**.
  Two different uses, two different views — see the `GossipSeam` contract below.
- **It de-risks the overlay.** Once anti-entropy guarantees convergence, the
  broadcast layer no longer has to be perfectly reliable, so **eager-flood to
  neighbours is sufficient and Plumtree's tree-repair sophistication becomes
  optional** (the review flagged HyParView+Plumtree as likely over-engineered for
  the tens–low-hundreds target). Reliability lives in anti-entropy, not the tree.
- **It bounds the delta buffer** even on today's full mesh — a standalone win.

Caveat to keep honest: BoundedCounter's targeted borrow (#643) relies on the
transfer *delta* arriving. Under this model a dropped transfer-delta is healed by
the next anti-entropy round — so it degrades to *higher latency*, not the silent
deny the review worried about. Worth a dedicated test.

## The `GossipSeam` — views into the endpoints

The clean abstraction is a `GossipSeam : Seam` that **provides two views of the
endpoints**:

- **active-neighbour view** — the ~k peers you push deltas to and GC against.
- **full-membership view** — everyone in the room, the pool anti-entropy samples.

For a regular `MeshSeam`/`LinkSeam` the two views are identical (every peer is a
neighbour), so Quilter's behaviour is unchanged there. A `GossipSeam` makes the
neighbour view a strict subset, and the scaling win materializes. Open question
(Phase 4): whether the neighbour view is a small addition to the `Seam` contract
(e.g. `activePeers` defaulting to `peers`) or injected into Quilter — decided when
the GossipSeam lands. Phase 1 only needs the *delta-target set* as a parameter
that defaults to full membership.

`GossipSeam` otherwise honours the `Seam` contract: single-collection `incoming`
(ADR-034), `broadcast`, `availability`; `sendTo` to a non-neighbour uses
flood-with-filter (low volume once per-delta acks are neighbour-scoped).

## Findings from the review, carried forward

- **Liveness reuse is a composer, not a tweak.** `HeartbeatPartitionDetector` is
  per-link and collects `link.incoming`; reusing it per-neighbour needs a
  SeamRoom-style composer that runs/teardowns detectors over a shared `incoming`
  fan-out as the active view churns. It belongs in `:kuilt-gossip`, not in
  `:kuilt-liveness`.
- **Firewall "neighbour-edge down" from "peer gone."** A live peer can lose its
  edge to me; that `PartitionEvent` must drive overlay repair, not logical-room
  membership.
- **A `:kuilt-gossip` virtual-time sim harness is required** (analogous to
  `RaftSimulation`/`MultiNodeRaftSim`): all gossip/anti-entropy timers injected,
  seeded RNG, bounded time-advance — never `advanceUntilIdle` (timers re-arm).
- **GRAFT-storm suppression + overlay-partition recovery** must be designed if a
  tree-based disseminator is used; with eager-flood + anti-entropy they largely
  fall away (anti-entropy heals partitions once connectivity returns).
- **Justify any tree sophistication** against a k-regular-flood baseline; don't
  assume Plumtree pays at the target scale.

## Module & discipline

- **`:kuilt-gossip`** (all targets) → depends on `:kuilt-core` + `:kuilt-liveness`;
  exposes `GossipSeam : Seam`, the membership/view manager, the disseminator, and
  the per-neighbour liveness composer. Layering verified clean (no back-edge into
  core).
- All timers take a **required injected dispatcher/scope**; all randomness uses an
  **injected seeded RNG** (repo time-and-randomness discipline).

## Phases

Phase 1 lives in `:kuilt-quilter` and stands alone; Phases 2–5 build `:kuilt-gossip`.
Each phase is its own PR, validated on the `:kuilt-scale` harness.

- **Phase 0 — Planning** (#653): this design doc.
- **Phase 1 — Delta-GC stability** (#654, **start here**): decouple delta-GC from
  full membership (GC against a *delta-target set* that defaults to full
  membership) + add a periodic random-peer anti-entropy reconcile (full-state
  first). Defaults preserve today's behaviour; improves the current full mesh.
- **Phase 2 — Membership/overlay**: the active-neighbour view + healing — a
  roster-derived k-regular view (favoured) or HyParView views, over
  `:kuilt-liveness` via the composer above.
- **Phase 3 — Dissemination**: eager-flood-to-neighbours with dedup (gossip header
  + seen-set + TTL); Plumtree only if a measured win justifies it. Plus the gossip
  sim harness.
- **Phase 4 — GossipSeam**: wrap Phases 2–3 as a `Seam` exposing both views; pass
  `SeamConformanceSuite`; measure O(N) broadcast vs the full-mesh baseline.
- **Phase 5 — Quilter integration**: wire Quilter onto the GossipSeam's two views;
  prove end-to-end ~O(N) replication at higher N on the harness.

Phases 2–5 are filed as the design firms (they may shift with the Phase-1
outcome). Docs fold into each phase.

## Out of scope / deferred

- Next-hop unicast routing over the overlay (flood-with-filter suffices).
- Digest/version-vector anti-entropy diff (full-state first; diff is the Phase-1.5
  optimization for large CRDTs).
- Gossip for the consensus path (Raft stays complete-graph).
