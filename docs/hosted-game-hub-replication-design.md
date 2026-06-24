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
each other; everything flows through the hub.** No traffic classes in the fabric, and
hidden information is dealt **publicly** (encrypted) via `kuilt-deal`.

## Background — what's needed

- **Chat is an RGA** (sequence CRDT). RGA ops carry causal dependencies (insert-after), so
  a missing op stalls the sequence until it is filled — it needs prompt, in-order replication.
- **A hosted game is a star:** the server holds one connection per client; each client holds
  one connection (to the server). The design intent — and what the data flow actually is —
  is to make the **hub→client** flow prompt so clients never need to talk to each other.

## Design

### Two transport concerns, no classes

The fabric exposes two send verbs, and they **are** the model:

- **`broadcast`** — "everyone may see this." Relayed/flooded across the overlay. Carries
  public game state, presence, chat, and **`kuilt-deal` ciphertext** (public by construction).
- **`sendTo(peer)`** — "exactly this peer." Point-to-point. Carries raft RPCs.

Secrecy is a property of the *payload*, not the transport: `DealSession` (`kuilt-deal`)
drives a cryptographically fair deal as an op-CRDT **broadcast over the seam**, with
per-card visibility set by which players **strip** their encryption layer — a cryptographic
visibility quorum. So a per-recipient secret never needs a private path; it is public
ciphertext that floods like any other broadcast. We **reject** a fabric-level traffic-class
system (typed channels / declared partitions / on-the-wire class tags): it would push peer-
and policy-semantics into the layer whose whole purpose is to mask them. (Fireworks' A6
per-seat `sendTo` disclosure is a transitional shape that public dealing can replace.)

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

### Reconnect → FullState (the gap-healing baseline)

The only gap source on a reliable star is a client **losing its connection**. Quilter already
heals this: on first contact with a (re)joining peer it sends a `FullState` (`sendFullStateTo`),
so a reconnecting client converges from the hub's current state without a delta replay. **No
cross-relay `Resend` is needed.**

### Hub-centric — no spoke→spoke

Clients replicate with the hub only. A spoke's `Seam.peers` stays its physical view
(`{self, hub}`); its Quilter delta-target/GC set is `{hub}`. We do **not** mask the star as a
full mesh, do **not** present full-membership `peers`, and do **not** route `sendTo` between
spokes. The star is honestly hub-centric — matching the intent and the actual data flow.

## Scope boundaries (what this does NOT do)

- **No fabric traffic classes** — secrecy is `kuilt-deal`'s job.
- **No spoke→spoke** addressing / routed `sendTo` / faithful-mesh masking.
- **No Quilter change** — the forward flow is `GossipSeam` relay; gap-healing is the existing
  FullState-on-reconnect.
- **No multi-hop / federated routing.**

## Escalations (documented next-actions, not built now)

A ladder, each with an explicit trigger. Until a trigger fires, the baseline (forward flow +
reconnect-FullState) is the design.

1. **Cross-relay prompt delta-repair.** Heal a mid-stream gap with a targeted delta-range
   resend instead of waiting for reconnect/anti-entropy. Two shapes: route `sendTo` to the
   origin (make the overlay a faithful N-peer `Seam` — needs full-membership `peers`), **or**
   hub-served repair (the hub serves the missing range from its merged/buffered state, relaxing
   the origin-only `onResend` guard, `Quilter.kt:761`). **Trigger:** mid-stream loss on a
   *healthy* link becomes real (lossy transport, `DeliveryPolicy.Lossy`), **or**
   FullState-on-reconnect of a long chat RGA proves too expensive and a delta-range resend is wanted.
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
2. **Reconnect heals.** B drops; A appends ops; B reconnects → receives `FullState` → converges.
   Proves the gap-healing baseline without cross-relay repair.

## Decisions recorded

- **Hub-centric baseline** (forward flow + reconnect-FullState); **no spoke→spoke**. Supersedes
  the earlier "faithful-mesh / routed `sendTo`" framing, now demoted to an **escalation**.
- **No fabric traffic classes** — secrecy via `kuilt-deal` public dealing.
- **Cross-relay prompt repair and full Plumtree are escalations** with explicit triggers.
- **Dependency:** hub on the `FullFanout` active-view policy (epic #794 phase 1) for rooms larger
  than the default active-view size.

## References

- Epic [#794](https://github.com/tractat-us/kuilt/issues/794); planning sub-issue [#795](https://github.com/tractat-us/kuilt/issues/795).
- `kuilt-core` `GossipSeam` / `GossipView` (`docs/gossip-mesh-design.md`); `kuilt-quilter` `Quilter` (`FullState` / repair path).
- `kuilt-deal` `DealSession` / `CommutativeScheme` — public cryptographic dealing.
- fireworks-compose [discussion #2904](https://github.com/tractat-us/fireworks-compose/discussions/2904), ADR-055.
