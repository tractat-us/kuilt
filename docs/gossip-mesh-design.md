# Partial-mesh gossip — design

> Status: **Phases 1–4 implemented**; Phase 5 (Quilter integration) is the
> remaining work. Originally revised 2026-06-22 after an architect review of the
> first cut, which found the original "drop acks, rely on anti-entropy" plan
> unsound and reframed where the real O(N²) cost lives. Implementation is phased;
> Phase 1 was the prerequisite. See the per-phase "as shipped" sections below.

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
neighbour view a strict subset, and the scaling win materializes. The Phase-4
open question — whether the neighbour view is a small addition to the `Seam`
contract (e.g. `activePeers` defaulting to `peers`) or injected into Quilter — is
**decided in favour of injection**; see "Phase 4 as shipped" below. Phase 1 only
needs the *delta-target set* as a parameter that defaults to full membership.

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

## Phase 1 as shipped

Phase 1 landed in PR #662 in `:kuilt-quilter`, with no new modules required. It
adds two constructor parameters to `Quilter` and a background loop, keeping all
defaults backward-compatible.

### Delta-target set

`Quilter` accepts a `deltaTargets: (Set<PeerId>) -> Set<PeerId>` parameter
(defaulting to the identity). `recomputeUniversalAck()` now mins `ackedThrough`
over `deltaTargets(knownPeers)` rather than the full membership. A `GossipSeam`
will supply the ~k active neighbours here; with a full `MeshSeam` or `LinkSeam`
the default identity means behaviour is unchanged. The practical effect: the GC
watermark is `min over k` peers instead of `min over N`, eliminating the O(N²)
delta-buffer growth that prevented sparse-mesh deployment.

### Anti-entropy backstop

`runAntiEntropy()` now calls `reconcileWithRandomPeer()` each tick. It picks one
peer uniformly at random from the full membership (`knownPeers`) and sends the
current post-merge full state via `sendTo`. The receiver merges idempotently
(join-semilattice), so delivery is order-independent and the send may safely be
repeated. This is full-state-first reconcile — a version-vector or Merkle-digest
diff is a later optimization.

### GC safety contract

GC'ing a delta once only the k delta-target neighbours have acked is safe because
the anti-entropy backstop guarantees every peer eventually receives the post-merge
full state. Convergence for peers outside the delta-target set no longer depends on
those peers acking every delta; they converge within one anti-entropy round after
a missed delivery. The backstop also heals dropped deltas within the neighbour set:
the next reconcile re-delivers the merged result regardless of what was lost.

### Named follow-ups

**(i) Digest-gated reconcile** (trigger: avg CRDT state exceeds a practical
threshold, e.g. >10 KB per round-trip). Full-state-every-round is O(state size)
per anti-entropy tick. The standard path — taken by mature anti-entropy systems
(Riak AAE, Cassandra repair) — is to send a compact version-vector or Merkle
digest first and ship only the diff. Add a `QuiltMessage.Digest` message and a
matching `onDigest` handler in `Quilter`; keep the full-state fallback for peers
that don't support the new message type. Tracked as #663 (act when CRDT sizes in
production justify the work).

**(ii) Anti-entropy fanout / scheduling** (trigger: room sizes where tail
convergence latency matters at scale). With fanout=1 and N peers, a single peer
that has only been reached via anti-entropy (never via a direct delta) needs O(N
log N) rounds on average before every peer has seen its state — the coupon-
collector tail. Mitigations: fanout > 1 (contact f random peers per round,
reducing the tail to O(N log N / f)), or round-robin over non-target peers to
guarantee every peer is covered within ⌈N/f⌉ rounds. Tracked as #664 (act when
measured convergence latency at large N justifies the added complexity).

## Phases

Phase 1 lives in `:kuilt-quilter` and stands alone; Phases 2–5 build `:kuilt-gossip`.
Each phase is its own PR, validated on the `:kuilt-scale` harness.

- **Phase 0 — Planning** (#653): this design doc.
- **Phase 1 — Delta-GC stability** (#654, **implemented** PR #662): decouple
  delta-GC from full membership (GC against a *delta-target set* that defaults to
  full membership) + add a periodic random-peer anti-entropy reconcile (full-state
  first). Defaults preserve today's behaviour; improves the current full mesh.
- **Phase 2 — Membership/overlay** (#657, **decided: roster-derived k-regular**):
  the active-neighbour view + healing, over `:kuilt-liveness` via the composer
  above. See "Phase 2 decision" below.
- **Phase 3 — Dissemination** (#658, **implemented**): eager-flood-to-neighbours
  with dedup (gossip header + seen-set + TTL) + the gossip sim harness. Plumtree
  rejected — see "Phase 3 as shipped" below.
- **Phase 4 — GossipSeam** (#659, **implemented**): wrap Phases 2–3 as a `Seam`
  exposing both views; pass `SeamConformanceSuite`; measure O(N)→O(k) broadcast vs
  the full-mesh baseline. See "Phase 4 as shipped" below.
- **Phase 5 — Quilter integration**: wire Quilter onto the GossipSeam's two views;
  prove end-to-end ~O(N) replication at higher N on the harness.

Phases 2–5 are filed as the design firms (they may shift with the Phase-1
outcome). Docs fold into each phase.

## Phase 2 decision: roster-derived k-regular view

The `needs-design` choice for #657 — roster-derived k-regular vs HyParView — is
settled in favour of **roster-derived k-regular**, validated against the
literature (HyParView paper; Montresor's gossip survey; Akka Cluster's ring+roster
failure detector; Erdős–Rényi connectivity):

- **Why not HyParView.** Its premise is the *absence* of a global roster, at N in
  the thousands — it builds a connected overlay with no node knowing full
  membership, paying for that with a shuffle/forward-join/passive-view protocol.
  kuilt already has a roster (`Room.roster` / the underlying `Seam.peers`) and
  targets tens–low-hundreds of peers, so HyParView's value proposition is absent
  and its complexity unjustified.
- **The rule.** Each peer derives its active view as a **seeded random k-out
  sample** of the roster (excluding self) — `partialView(self, roster, k, …)` in
  `:kuilt-gossip`. Random k-out (not a hash ring) is robust against skewed peer-id
  distributions. The union of every peer's k-out edges is connected with high
  probability once `k ≳ ln N` (Erdős–Rényi threshold).
- **k.** `recommendedActiveViewSize(N) = max(4, ⌈ln N⌉ + 2)` ⇒ k ≈ 4–7 for the
  target range. The `+2` is redundancy against simultaneous failures; the floor of
  4 keeps small rooms robust.
- **Healing.** Recompute the view on roster change, with **per-peer jitter**
  (50–200 ms) to avoid a synchronized recompute churn-storm. For a neighbour that
  crashes *before* its roster tombstone propagates, keep a small ordered **spare
  list** (the one piece of HyParView worth borrowing) for immediate reactive
  substitution. Anti-entropy (Phase 1) covers any residual gap.
- **Failure signal.** The per-link `HeartbeatPartitionDetector` from
  `:kuilt-liveness`, composed per-neighbour. SWIM-style indirect probing / phi-
  accrual are deliberately *not* adopted now — revisit only for lossy/high-jitter
  WAN topologies.

Phase 2's first slice (the pure `PartialView` selection + `recommendedActiveViewSize`,
with a union-connectivity property test) lands separately; the `GossipView`
manager (liveness composition + jittered healing) and `GossipSeam` follow.

## Phase 3 as shipped: eager-flood dissemination + sim harness

The `needs-design` choice for #658 — Plumtree vs eager-flood — is settled in
favour of **eager-flood-to-neighbours with dedup**. Plumtree's tree-repair
(eager push along a spanning tree + lazy IHAVE GRAFT to recover) earns its
complexity only when the broadcast layer must be *reliable on its own*. It needn't
be here: anti-entropy (Phase 1) already guarantees convergence, so the flood only
has to be *usually* effective, and GRAFT-storm suppression + overlay-partition
recovery fall away (anti-entropy heals partitions once connectivity returns). At
the tens–low-hundreds target the flood's redundant sends are bounded by k, not N.

- **Frame.** `GossipSeam.broadcast` wraps the payload in a `GossipFrame`:
  `[MAGIC][version][ttl][origin][seq][payload]`. The message id is `(origin, seq)`
  — the origin's id plus a per-origin monotonic sequence — identical across every
  relayed copy.
- **Flood + relay.** The origin floods the frame to its ~k active neighbours. A
  receiver delivers the payload to the app **once** (keyed by message id in a
  seen-set), attributes it to the *origin* (not the relay hop), and — while
  `ttl > 1` — decrements the budget and re-floods to *its own* active neighbours
  **minus the peer it arrived from**. So the broadcast walks the overlay
  device-to-device along k-regular edges.
- **Termination.** Dedup is what stops the flood: a node relays each message at
  most once. The TTL (default 16, well above the k-regular overlay diameter) is
  only a hard cap against pathological loops. A node also drops its *own* broadcast
  echoed back.
- **`sendTo` stays unwrapped** — point-to-point, no header, no relay (a full-mesh
  base reaches any peer directly). A receiver tells a relay frame from a raw
  `sendTo` frame (and from a heartbeat ping/pong, a distinct text prefix) by the
  frame magic + structural decode.
- **Cost.** Per broadcast, total relay sends are ≈ N·k (each of N nodes floods to
  ~k neighbours once) — sub-quadratic vs the full-mesh N·(N−1). The sim harness
  asserts this bound and single-delivery convergence.
- **Sim harness.** `GossipSimulation` (in `:kuilt-gossip` commonTest) is the
  `RaftSimulation` analogue: N in-memory peers over a full-mesh
  `InMemoryGossipNetwork`, per-peer seeded RNG, the test clock, bounded
  `awaitTrue`/`settle` that fail fast with a state dump — **never**
  `advanceUntilIdle` (heartbeat timers re-arm). It proves broadcast-reaches-all,
  the O(N·k) message bound, and re-formation + dissemination after churn.

Deferred: the seen-set is currently unbounded (grows with distinct broadcasts
seen); a per-origin high-water-mark bound is a v1 follow-up — at the target scale
the set stays small relative to live CRDT state.

## Phase 4 as shipped: GossipSeam through the TCK + the O(k) broadcast measurement

Phase 4 wraps Phases 2–3 as a conforming `Seam` and measures the broadcast win.

- **Seam termination contract.** `GossipSeam.incoming` now **completes when the base
  seam tears** (`Torn`): the app-frame surface is a buffered channel closed when the
  single `base.incoming` collector ends, not a never-completing `SharedFlow`. This
  satisfies the `Seam.incoming` contract that consumers (e.g. Quilter) rely on to
  self-clean — and incidentally fixes a latent drop of frames delivered before a
  collector subscribed.
- **Passes `SeamConformanceSuite`.** Verified over a real `InMemoryLoom` base (genuine
  `Torn`/`close`/`PeerNotConnected` lifecycle), via a test-only `GossipLoom` adapter that
  wraps each woven base seam in a started `GossipSeam`. The base is deliberately *not* the
  simulation-only `InMemoryGossipNetwork` (its mesh seam reports a constant `Woven` state
  with a no-op `close`, so it can't exercise the lifecycle invariants). Two pieces of
  plumbing let a started, timer-driven seam fit a TCK built for stateless fabrics: the
  suite gained a backward-compatible `newLoomPair(testScope)` overload (started seams run
  their background work on `backgroundScope`, cancelled before `runTest`'s terminal
  advance so the heartbeat timers don't spin it), and `GossipSeam` gained a `jitter`
  parameter the harness sets to zero for synchronous view convergence.
- **O(k) broadcast, measured.** On the `:kuilt-scale` harness, one broadcast's per-node
  relay fan-out stays bounded by *k* as N grows — N=10/20/40 ⇒ max per-node fan-out
  5/5/6 (= k), total relay sends 44/95/237 (≤ N·k), reaching all peers — versus a
  full-mesh flood's per-node N−1 (9/19/39). This is the Phase-3 `relaySendCount ≤ N·k`
  bound on the published harness.

### Phase 4 decision: Quilter wiring — inject, don't widen the contract

The deferred Phase-4 question (neighbour view as a `Seam`-contract addition vs injected
into Quilter) is **decided in favour of injection**, reusing the mechanism Phase 1 already
built:

- **The rule.** Phase 5 wires `Quilter(seam = gossipSeam, deltaTargets = { gossipSeam.activePeers.value })`.
  Anti-entropy continues to sample full membership (`seam.peers` / `knownPeers`). The
  "two views" are consumed as: deltas/GC against `activePeers`, anti-entropy over `peers`.
- **Why not add `activePeers` to the `Seam` contract.** It would widen `:kuilt-core` with
  an overlay-specific concept for a single consumer, and permanently commit the contract to
  a feature still settling pre-1.0. The dependency arrow stays clean — only `:kuilt-gossip`
  knows about partial views; `:kuilt-core` never learns of gossip.
- **Reactivity is sufficient.** `Quilter.recomputeUniversalAck` calls `deltaTargets(knownPeers)`
  afresh on every ack, so a lambda returning `activePeers.value` tracks the live active view
  without needing a `StateFlow` on the contract. A full `MeshSeam`/`LinkSeam` keeps the
  default identity `deltaTargets` and is unchanged.

## Out of scope / deferred

- Next-hop unicast routing over the overlay (flood-with-filter suffices).
- Digest/version-vector anti-entropy diff (full-state first; diff is the Phase-1.5
  optimization for large CRDTs).
- Gossip for the consensus path (Raft stays complete-graph).
