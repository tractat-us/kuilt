# Seam lifecycle (`SeamState`) — design

**Status:** approved design, pre-implementation
**Date:** 2026-05-30
**Scope:** `kuilt-core` contract change + every fabric adapter + `kuilt-session` consumer fix + conformance/session test coverage.

## Problem

A joiner can be handed a `Seam` whose fabric is not yet able to carry frames,
broadcast into it, and have the frame silently vanish — with no error, no
suspension, and no observable signal. The higher-level consumer then waits for a
reply that can never arrive and hangs until an unrelated timeout fires.

This is not a single transport's bug. It is a **gap in the `Seam` contract**, and
it surfaces three distinct defects:

1. **No readiness contract.** `Loom.weave()` returns a `Seam`, but the *meaning*
   of that return varies per fabric:
   - Relay/loopback fabrics (WebSocket, in-memory): the medium is already able to
     carry frames — `peers.size > 1` essentially immediately.
   - Radio/mesh fabrics (Multipeer, and by inspection WebRTC): `weave()` returns
     as soon as the *local* session object exists, **before** the underlying link
     reaches its connected state. There is a window where `peers == {selfId}` and
     a send reaches no one.

   So "I hold a `Seam`" means "I can send" on some fabrics and "maybe later" on
   others. Consumers have no contractual way to tell the difference.

2. **`broadcast` to an empty peer set is undefined, and silently a no-op.** The
   contract says only *"Suspends until accepted by the local transport."* With no
   peers that is vacuously satisfied — the frame is dropped with no signal.

3. **The conformance suite cannot catch this.** Every `SeamConformanceSuite` test
   awaits **both** `host()` and `join()` before sending, so it never exercises the
   "send before the fabric is live" window. Every fabric passes conformance, yet a
   radio fabric breaks in the field. New fabrics (WebRTC, Nearby) will rediscover
   this the hard way.

## The three axes

The bug conflated three questions that `peers == {selfId}` cannot distinguish:

| Axis | Question | Granularity | Status |
|------|----------|-------------|--------|
| **1. Fabric lifecycle** | *Can I inject frames into the fabric at all?* | aggregate (one value) | **missing — this design** |
| **2. Membership** | *Who is reachable right now?* | `peers: StateFlow<Set<PeerId>>` | exists |
| **3. Per-link health** | *What is the state of each transport composing the fabric?* | per-link map | future (see `2026-05-30-multi-transport-ply-fabric-brief.md`) |

This design adds **axis 1** and pins down send semantics against it. Axis 3 is
explicitly out of scope but the axis-1 semantics are defined so that axis 3 slots
in additively, with no contract break.

## Design

### 1. `SeamState` — the fabric lifecycle (new, `kuilt-core`)

```kotlin
public interface Seam {
    // ... existing members ...

    /** The fabric's lifecycle as observed by this peer. */
    public val state: StateFlow<SeamState>
}

/**
 * One peer's view of whether the fabric can carry frames.
 *
 * Orthogonal to [Seam.peers]: [Woven] with `peers == {selfId}` is a fully
 * legitimate, well-defined state — the fabric is live and this peer is simply
 * alone in the session. "Can I inject frames" (this) and "who can I reach"
 * (peers) are different questions.
 */
public sealed interface SeamState {
    /** The fabric is forming. Sends may reach no one and must not be relied on. */
    public data object Weaving : SeamState

    /** The fabric is live. Frames broadcast now are carried to the current peers. */
    public data object Woven : SeamState

    /** Terminal. The fabric is gone; [reason] says why. */
    public data class Torn(val reason: CloseReason) : SeamState
}
```

Lifecycle: `Weaving → Woven → Torn(reason)`. `Woven → Weaving` is permitted if a
fabric supports re-establishment; `Torn` is terminal.

**Rollup semantics (Ply-ready).** `state` is defined as an aggregate over the
fabric's constituent links ("plies"):

- `Weaving` — **no** ply is live yet.
- `Woven` — **at least one** ply is live (a frame can be injected into the fabric
  *somewhere*). Note this deliberately does **not** assert all peers are reachable
  — that is axis 2's job; an unreachable region simply means its peers are absent
  from `peers`.
- `Torn(reason)` — all plies gone / the seam was closed locally.

Today every fabric has exactly one ply, so the aggregate trivially equals that
single link's state. When composite (multi-ply) fabrics arrive, a per-ply view is
added **additively** (`plies: StateFlow<Map<PlyId, SeamState>>`) and the aggregate
keeps the same meaning ("any ply `Woven` ⇒ `Woven`"). Defining the aggregate this
way now is what makes axis 3 a non-breaking addition later.

### 2. `CloseReason` — add `Unreachable` (`kuilt-core`)

`CloseReason` already covers `Normal`, `Error(throwable)`, `RemoteRequested`. Add
one variant so the "never wove" failure is actionable rather than a generic error:

```kotlin
/** The fabric never wove — e.g. the join target was absent or the handshake timed out. */
public data object Unreachable : CloseReason
```

A joiner that cannot reach anyone transitions `Weaving → Torn(Unreachable)`. That
is the difference between "hangs forever" and "failed, here is why" — the
actionable-error property this design exists to provide. (`CloseReason` is a
sealed hierarchy designed to grow, so this is non-breaking for exhaustive `when`s
that already handle the common cases.)

### 3. Send semantics pinned down (no more silent drop)

| Call | Fabric state | Behaviour |
|------|--------------|-----------|
| `broadcast(p)` | `Weaving` or `Woven` with no other peers | **defined no-op, observable** (logged at warn/debug) — never silent. Broadcasting to no one is legitimate. |
| `broadcast(p)` | `Woven` with peers | delivered to all current peers. |
| `broadcast(p)` / `sendTo(..)` | `Torn` | throws — the seam is dead. |
| `sendTo(peer, p)` | `peer ∉ peers` | **throws** `PeerNotConnected` (new). Addressing an absent peer is a real error, not a no-op. |

`broadcast` and `sendTo` differ on purpose: broadcast-to-no-one is the "I might be
first/alone" case and must stay a no-op; `sendTo` names a specific peer, so an
absent target is unambiguously an error. (Making `broadcast` throw would just push
a catch-and-retry loop back onto the consumer — the wrong fix.)

A new exception type in `kuilt-core`:

```kotlin
public class PeerNotConnected(public val peer: PeerId) :
    IllegalStateException("peer not connected: ${peer.value}")
```

### 4. Consumer fix — `SeamRoom` (`kuilt-session`)

The brittle `peers.filter { it.size > 1 }.first()` poke (the interim hotfix)
becomes a fabric-agnostic await on lifecycle:

```kotlin
private suspend fun runMainLoop() {
    if (_role.value == SessionRole.Joiner) {
        seam.state.first { it is SeamState.Woven }   // fabric live before we speak
        sendHello()
    }
    seam.incoming.collect { swatch ->
        rawIncoming.emit(swatch)
        dispatchIncoming(swatch)
    }
}
```

This is correct on every fabric — relay (`Woven` immediately) and radio (`Woven`
when the link connects) — without `SeamRoom` knowing anything transport-specific.
It supersedes the interim hotfix.

> **Out of scope but noted:** application-level "all/quorum peers caught up"
> (`checkpoint()` / `quorum()`) is a session-layer concern, not a `Seam` contract.
> It needs per-peer delivery acknowledgement that not every fabric provides, and
> "caught up" is application stream-position semantics that `kuilt-core`
> deliberately does not model. It generalises the admit handshake and belongs in
> `kuilt-session` or above. Not part of this work.

## Test coverage (the part that stops the next fabric repeating this)

This is the core of the robustness goal — the timing invariants must live at the
library level so every current and future fabric is held to them.

1. **`SeamConformanceSuite` (in `kuilt-conformance`) gains lifecycle invariants**
   every fabric must pass:
   - `state` starts `Weaving` (or reaches `Woven`) and a host's `state` reaches
     `Woven` even with `peers == {selfId}`.
   - Once `Woven`, a broadcast is delivered to a joined peer (existing assertion,
     now gated on `Woven`).
   - A broadcast issued while `Weaving` does **not** silently disappear — the
     post-`Woven` send still arrives and order is preserved.
   - `sendTo(unknownPeer)` throws `PeerNotConnected`.
   - `close()` drives `state` to `Torn(Normal)`.

2. **A delayed-`Woven` fake `Loom`/`Seam`** — a test fabric whose `state` stays
   `Weaving` for a window after `weave()` returns, mimicking radio fabrics. This is
   the harness that reproduces the original bug class at the library level
   (in-memory/relay fabrics weave instantly and cannot, alone, exercise the
   window). Lives where the conformance/session tests can drive it.

3. **`kuilt-session` regression test** — `SeamRoom` against the delayed-`Woven`
   fake, asserting Hello is **not** broadcast while `Weaving` and **is** broadcast
   immediately on `Woven`. (Supersedes the interim hotfix's `peers`-based version.)

## Implementation surface

Adding `state` to the `Seam` interface is a breaking change for every
implementation — acceptable under the pre-1.0 aggressive posture, but each must be
updated:

- `kuilt-core`: `SeamState`, `PeerNotConnected`, `CloseReason.Unreachable`,
  `InMemoryLoom`'s `Seam` (drives `Woven` at construction), `broadcast`/`sendTo`
  semantics on the reference impl.
- `kuilt-websocket`: `KtorClientLoom` / `KtorServerLoom` seams drive
  `Weaving → Woven` on WS open, `Torn(reason)` on close/error.
- `kuilt-mdns`: discovery feeds a websocket seam — inherits its lifecycle.
- `kuilt-multipeer`: drive `Woven` from the `MCSession` `.connected` state
  callback (the exact event the bug was racing); `Torn(Unreachable)` on
  establish-timeout; `Torn(reason)` on disconnect.
- `kuilt-webrtc`, `kuilt-nearby`: same pattern — held honest by the conformance
  suite from day one.
- `kuilt-conformance`: new suite tests + the delayed-`Woven` harness.
- `kuilt-session`: `SeamRoom.runMainLoop` await + regression test.

## Sequencing (initial PRs — fast unblock first)

See the implementation plan for detail. Summary:

1. **Interim hotfix lands first** (the existing `peers.size > 1` PR) → publish →
   the consumer is unblocked on a published artifact while this work proceeds.
2. **Core contract** (`SeamState`, `CloseReason.Unreachable`, `PeerNotConnected`,
   `InMemoryLoom`, conformance suite tests + delayed-`Woven` harness) — one PR.
   This is the foundation; everything else depends on it.
3. **Fabric adapters** drive `state` — can fan out per fabric once the contract
   lands (websocket, multipeer, mdns, webrtc, nearby).
4. **`SeamRoom` switches to `state == Woven`**, removing the interim poke.
```
