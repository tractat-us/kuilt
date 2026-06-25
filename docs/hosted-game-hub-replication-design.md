# Hosted-game live replication over a server hub

> Status: **Draft / proposed.** Feeds ADR-005 (planning sub-issue [#795](https://github.com/tractat-us/kuilt/issues/795)) and epic [#794](https://github.com/tractat-us/kuilt/issues/794). Consumer driver: fireworks-compose [discussion #2904](https://github.com/tractat-us/fireworks-compose/discussions/2904) / ADR-055.
> Scope: the **single-server hub** over **reliable transport** (WebSocket/TCP). Lossy transports, the partial-mesh interior, and M-server federation are **out of scope** (see Escalations).

## What this is, in plain terms

A hosted game has one server and many clients, each holding a single connection. The
server is a **hub** — the only relay between clients. We want a client's chat and game
state to reach the others **promptly**. The design: the hub **eager-floods** each
client's updates to all the others over the reliable connections it already holds. That
forward flow is prompt and in-order, so a sequence CRDT (RGA chat) applies updates as
they arrive. The only thing that interrupts it is a client **losing its connection** —
healed when it reconnects and the hub hands it the current state. **Clients never talk to
each other; everything flows through the hub.** No traffic classes in the fabric: there is
exactly one rule — a **`broadcast` floods to everyone, a `sendTo` reaches one peer and is
never relayed** — so anything addressed to a single peer stays private to that peer. Hidden
information can *additionally* be dealt **publicly** (encrypted) via `kuilt-deal`, but the
fabric's unicast guarantee for `sendTo` stands on its own and does not depend on it.

## Background — what's needed

- **Chat is an RGA** (sequence CRDT). RGA ops carry causal dependencies (insert-after), so
  a missing op stalls the sequence until it is filled — it needs prompt, in-order replication.
- **A hosted game is a star:** the server holds one connection per client; each client holds
  one connection (to the server). The design intent — and what the data flow actually is —
  is to make the **hub→client** flow prompt so clients never need to talk to each other.

## Deployment topology (scope — regime 1 only)

This design targets the case where **every participant holds a direct, reliable link to one
server** (each device opens a WebSocket to the hub). Device heterogeneity is then invisible —
an iPhone, a browser, and a desktop all speak WebSocket to the server identically, and the
topology is a flat reliable star. This is fireworks' deployment today (single Fly.io server).

**Known and explicitly out of scope** — heterogeneous / peer-to-peer topologies, where the
substrate forms a **tree or partial mesh** instead of a flat star:

- a participant reachable only *through another participant* (e.g. a `kuilt-webrtc` browser
  peer behind a peer-relay, or a `kuilt-nearby` iPhone reachable only via another iPhone) →
  **multi-hop**, spoke↔spoke relaying required;
- no universally-reachable node (pure P2P) → **partial mesh over lossy links**.

These are real, first-class kuilt targets (the `kuilt-nearby` / `kuilt-webrtc` modules exist),
**not** corner cases — but they are served by the **existing gossip overlay** (`GossipSeam` with
the **random-k-out** active-view policy + anti-entropy) and the Escalations below, *not* by this
baseline. The unifier is the epic [#794](https://github.com/tractat-us/kuilt/issues/794)
**active-view policy**: `FullFanout` = this server star; random-k-out = the P2P mesh (already
built); a future `TwoTier`/tree policy = the multi-hop case. We are aware of these regimes and
are deliberately deferring them.

## Design

### Two transport concerns, no classes

The fabric exposes two send verbs, and they **are** the model:

- **`broadcast`** — "everyone may see this." Relayed/flooded across the overlay. Carries
  public game state, presence, chat, and **`kuilt-deal` ciphertext** (public by construction).
- **`sendTo(peer)`** — "exactly this peer." Point-to-point. Carries raft RPCs.

**The unicast invariant — a first-class, tested fabric guarantee.** `sendTo(peer)` is
point-to-point and is **never** relayed or fanned out — not by the `FullFanout` hub, not by
any active-view policy. Only `broadcast` floods. This makes the *send verb itself* the leak
boundary: a per-recipient payload sent via `sendTo` reaches exactly its addressee and no one
else, **independent of whether the payload is also cryptographically protected**. A consumer
that has not adopted public dealing relies on this transport guarantee alone to keep
per-recipient hidden information from leaking across peers; a consumer that has adopted public
dealing gets defence in depth. The invariant is **tested, not merely asserted** (Testing §3),
because a consumer may depend on it without the cryptographic backstop.

Secrecy can *additionally* be made a property of the *payload*, not the transport:
`DealSession` (`kuilt-deal`) drives a cryptographically fair deal as an op-CRDT **broadcast
over the seam**, with per-card visibility set by which players **strip** their encryption
layer — a cryptographic visibility quorum. So a per-recipient secret *can* travel as public
ciphertext that floods like any other broadcast. But public dealing is an **optional**
payload-level protection layered on top of the unicast invariant, **not a prerequisite** for
it — and it is deferred indefinitely by the immediate consumer, so the baseline must keep
per-recipient payloads safe on the transport guarantee alone. We **reject** a fabric-level
traffic-class system (typed channels / declared partitions / on-the-wire class tags): it would
push peer- and policy-semantics into the layer whose whole purpose is to mask them — the single
"`broadcast` floods, `sendTo` never does" rule is the entire model.

### Prompt forward flow (the baseline)

The hub eager-floods at the **seam** layer. When client A applies an RGA op, A's Quilter
`broadcast`s a delta; on `GossipSeam` that floods to A's active view = `{hub}`. The **hub's
`GossipSeam`**, receiving the broadcast frame, **re-floods it to its active neighbours minus
A** (`dispatchInbound`) — so every other client receives A's op via the hub's relay, stamped
`sender = A`, and applies it. This is seam-level relay; Quilter is not involved in the fan-out.

For the hub to reach **every** client its active view must cover all spokes. The `FullFanout`
active-view policy (epic #794 phase 1) provides this guarantee; for small rooms (Hanabi-scale,
N within the default active-view size) the default `GossipView` k-out already selects everyone,
but `FullFanout` makes it explicit and size-independent.

Over reliable transport each hub→client send is **ordered and lossless** (`base.sendTo`
suspends under backpressure, it does not drop), so A's ops arrive at every client in causal
order and the RGA applies them immediately. **No gaps in the common case.**

### Reconnect → convergence (the gap-healing baseline)

The only gap source on a reliable star is a client **losing its connection**. A reconnecting
client converges to the hub's current state from a fresh empty replica — **no cross-relay
`Resend` is needed**. Two layers cooperate:

- **Membership.** With host liveness monitoring (`gameHost(livenessConfig = …)`), a dropped
  voter that stops answering heartbeats is evicted after `reconnectWindow` and its seat re-opened,
  so a reconnecting peer is re-admitted via `gameJoin`. (Without `livenessConfig` the seat never
  frees and a full roster rejects the rejoin with `RosterFullException` — liveness is therefore
  required for reconnect, not optional.)
- **State.** The reconnecting replica converges to the full history **promptly**, not at
  anti-entropy cadence, via the lagging-FullState push-back (fix [#828](https://github.com/tractat-us/kuilt/issues/828)).

**How the heal works.** When a reconnecting Quilter starts, `onPeersChanged` fires and calls
`sendFullStateTo(hub)` — sending its own (empty) FullState to the hub *after* its collector has
subscribed. The hub's `onFullState` detects that the incoming state is strictly dominated by its
own current state (`merged == current && msg.state != current`) and immediately calls
`sendFullStateTo(reconnectingPeer)` to push the full history back. The heal is
**synchronous with the first-contact exchange**, independent of the anti-entropy backstop timing.

### Hub-centric — no spoke→spoke

Clients replicate with the hub only. A spoke's `Seam.peers` stays its physical view
(`{self, hub}`); its Quilter delta-target/GC set is `{hub}`. We do **not** mask the star as a
full mesh, do **not** present full-membership `peers`, and do **not** route `sendTo` between
spokes. The star is honestly hub-centric — matching the intent and the actual data flow.

## Scope boundaries (what this does NOT do)

- **No fabric traffic classes** — secrecy is `kuilt-deal`'s job.
- **No spoke→spoke** addressing / routed `sendTo` / faithful-mesh masking.
- **No multi-hop / federated routing.**

## Escalations (documented next-actions, not built now)

A ladder, each with an explicit trigger. Until a trigger fires, the baseline (forward flow +
reconnect convergence) is the design.

0. **Prompt reconnect heal — fixed in [#828](https://github.com/tractat-us/kuilt/issues/828).**
   `Quilter.onFullState` now detects a strictly-dominated incoming state and pushes its full
   history back to the sender immediately. The reconnecting Quilter's first-contact FullState
   (sent from `onPeersChanged` after its collector subscribes) triggers this heal — see
   "Reconnect → convergence" above.
1. **Cross-relay prompt delta-repair.** Heal a mid-stream gap with a targeted delta-range
   resend instead of waiting for reconnect/anti-entropy. Two shapes: route `sendTo` to the
   origin (make the overlay a faithful N-peer `Seam` — needs full-membership `peers`), **or**
   hub-served repair (the hub serves the missing range from its merged/buffered state, relaxing
   the origin-only `onResend` guard, `Quilter.kt:761`). **Trigger:** mid-stream loss on a
   *healthy* link becomes real (lossy transport, `DeliveryPolicy.Lossy`), **or**
   FullState-on-reconnect of a long chat RGA proves too expensive and a delta-range resend is wanted.
   **Guard (non-negotiable):** any routed `sendTo` that lands here must remain **unicast** —
   routed to the single addressed peer (`spoke→hub→spoke`), **never** fanned out. A routed
   `sendTo` that floods would break the unicast invariant and leak per-recipient payloads across
   peers (in the trusted-leader regime with no `kuilt-deal` backstop, that is a direct
   hidden-information leak). The escalation makes `sendTo` *reachable*, never *broadcast*.
2. **Full Plumtree** (lazy-push `IHAVE` digests + `GRAFT`). Proactive gap discovery +
   alternate-path repair. **Trigger:** a true partial-mesh interior at scale (M-server core /
   k-regular overlay) where dropped floods have *alternate* paths worth grafting and anti-entropy
   latency is too high as the sole backstop.

## Testing

Virtual time throughout: `StandardTestDispatcher` + bounded `advanceTimeBy`/`runCurrent`, seeded
RNG, never `advanceUntilIdle` (gossip timers re-arm).

1. **Prompt forward flow (gating).** `gameHost` over a star `meshSeam` + `GossipSeam` (hub on
   `FullFanout`), spokes A/B/C. A `broadcast`s a sequence of RGA ops; assert B and C apply them
   within a bounded window **and in order**. Proves prompt, ordered chat.
2. **Reconnect converges promptly.** With host `livenessConfig`: B drops; the leader evicts it
   after `reconnectWindow` and re-opens admission; A appends ops while B is away; B rejoins
   (fresh identity) → converges to the full history **promptly via FullState push-back** (fix
   [#828](https://github.com/tractat-us/kuilt/issues/828)). The test uses production
   `antiEntropyInterval` (1 minute) to prove that only the prompt lagging-FullState heal
   delivers within the virtual-time budget — not the anti-entropy backstop. A separate test
   proves the cluster **survives** a drop at all (no crash; survivors keep replicating) —
   this required fixing `SeamRaftTransport.sendTo` to honor its documented "may silently drop
   if peer is unreachable" contract (it was throwing `PeerNotConnected`, crashing the engine
   when a voter dropped).
3. **Leak boundary — `sendTo` is never relayed (gating).** Under `FullFanout`, the hub
   `sendTo`s a frame to spoke A; assert A receives it and **B never observes it**. Locks the
   unicast invariant that protects per-recipient payloads regardless of payload-level secrecy —
   the consumer relies on this *without* the `kuilt-deal` cryptographic backstop.

## Decisions recorded

- **Hub-centric baseline** (forward flow + reconnect convergence); **no spoke→spoke**. Supersedes
  the earlier "faithful-mesh / routed `sendTo`" framing, now demoted to an **escalation**.
- **Reconnect heals via lagging-FullState push-back (prompt), not anti-entropy backstop** — fixed
  in [#828](https://github.com/tractat-us/kuilt/issues/828). `Quilter.onFullState` pushes back
  its full state when it receives a strictly-dominated incoming state; the reconnecting Quilter's
  own first-contact FullState (from `onPeersChanged`, after its collector subscribes) is the
  trigger. The earlier anti-entropy-only path was escalation #0, now resolved.
- **`SeamRaftTransport.sendTo` made best-effort** (catches `PeerNotConnected`, per its documented
  contract) so a dropped voter no longer crashes the engine — the prerequisite for drop-survival.
- **No fabric traffic classes.** The leak boundary is the **unicast invariant** — `sendTo` is
  never relayed/fanned-out, only `broadcast` floods — a **tested**, first-class fabric guarantee
  that holds **with or without** `kuilt-deal`. Public cryptographic dealing is an *optional*
  payload-level protection (deferred indefinitely by the immediate consumer), not a prerequisite;
  the baseline keeps per-recipient payloads safe on the transport guarantee alone.
- **Cross-relay prompt repair and full Plumtree are escalations** with explicit triggers; any
  routed `sendTo` escalation stays **unicast** (never fans out).
- **Dependency:** hub on the `FullFanout` active-view policy (epic #794 phase 1) for rooms larger
  than the default active-view size.

## References

- Epic [#794](https://github.com/tractat-us/kuilt/issues/794); planning sub-issue [#795](https://github.com/tractat-us/kuilt/issues/795).
- `kuilt-core` `GossipSeam` / `GossipView` (`docs/gossip-mesh-design.md`); `kuilt-quilter` `Quilter` (`FullState` / repair path).
- `kuilt-deal` `DealSession` / `CommutativeScheme` — public cryptographic dealing.
- fireworks-compose [discussion #2904](https://github.com/tractat-us/fireworks-compose/discussions/2904), ADR-055.
