# Star relay: making a room's roster genuinely routable

Design for [#1994](https://github.com/tractat-us/kuilt/issues/1994). Written 2026-08-03.

**Revision 2**, after a Fable design review. Revision 1 contained a remotely-triggerable co-joiner
takeover (§C1 below), suspended its fan-out inside the inbound collector, claimed a false CBOR
invariant, and gated its own de-risking prototype on a tautology. Those are corrected here and the
deltas are recorded in [What revision 1 got wrong](#what-revision-1-got-wrong) so the reasoning is not
lost.

**Revision 3** — two corrections folded back from implementation, so this document is not left
contradicting the code it describes. See
[Revision 3 corrections](#revision-3-corrections-folded-back-from-implementation).

## The problem, restated

`RoomChannelSeam` publishes the **roster** as `Seam.peers` (`RoomChannel.kt:133`) but routes `sendTo`
through the **transport** (`:154`). On a star fabric those are different sets, so a `Quilter` over a
room channel targets peers it can never address.

Two corrections to the issue's own analysis, both material:

1. **`broadcast` does not "work".** The issue's table marks it surviving. It does not deliver.
   `MuxServerLoom.readLoop` spools a spoke's frame into the *host's* `incoming` and stops — for
   `broadcast` exactly as for `sendTo`. Pinned by #1588's
   `spokeFramesReachOnlyTheHostNeverAnotherSpoke` (`StarTopologyPeerRoutingTest.kt:141`). So **no**
   frame a joiner sends reaches a co-joiner, and `Room.broadcast`'s own KDoc ("to all other admitted
   members") is broken too. This rules out the issue's option 2 as a standalone fix: intersecting
   `peers` with the transport makes the Quilter honest but still non-convergent, because there is no
   host re-broadcast to depend on.

2. **kuilt already ships a relay dialect — privately, in `:kuilt-cluster`.** `RaftRelay(origin, dest,
   bytes)` + `RaftRelayHub` carry an origin that is never re-stamped, reject a spoke forging another's
   id via `validFirstHop`, and drop an illegal `dest` rather than re-forwarding it.
   `RoutedUnicastRouter` adds the second hop under an explicit leak boundary. Decisively:
   `ClusterClient` rides `Room.channel("raft")` — the very `RoomChannelSeam` #1994 calls broken — and
   works, because the cluster layer *never trusts `Seam.peers` for routing*; `NoPeerRaftTransport`
   forces every send through the relay.

So the defect is not "kuilt lacks relay". It is that the relay dialect is unavailable outside
`:kuilt-cluster`, so a `Quilter` over a room channel takes the roster at face value — the one thing
the cluster layer learned not to do. (`Quilter.kt:502` already says "or a relayed delivery was
dropped": it anticipated a relay it never got.)

## Decisions

| Question | Decision |
|---|---|
| Honesty vs convergence | **Relay.** Make the star behave like a mesh at the Room layer so the existing contracts become true. Only option meeting the issue's done criteria. |
| Layer | **Session** (`SeamRoom` host). Covers all four no-route fabrics (`MuxServerLoom` hub, `hubMesh` spoke, `:kuilt-multipeer`, `:kuilt-nearby`) because each gets a `SeamRoom` host; reuses the roster, admit gate and `ProtocolVersion` already there. A core-layer relay would cover one fabric and would have to push the roster down into it, re-implementing the admit protocol a layer lower. |
| Policy | **Unconditional.** Every `SeamRoom` in Host role relays. No knob. |
| Old peers | **Move the `ProtocolVersion` floor to 2 *and* close the `isSupported(null)` carve-out.** See [Version 2](#version-2-and-the-limits-of-the-gate) — a permissive `null` would re-admit precisely the population the bump exists to exclude. |
| Reuse | **Lift `validFirstHop` only.** `resolveRecipients` stays session-local: it is `PeerId`-shaped, while `RaftRelayHub` delivers to a `MutableSharedFlow<RaftEnvelope>` rather than to a set of peers. Forcing the cluster onto it would be a downgrade. There is consequently **no** cluster-migration follow-up. |
| Frame prefix | **[#2007](https://github.com/tractat-us/kuilt/issues/2007)'s registry lands first, as slice 0.** The relay claims `0x72` from the registry rather than adding a sixth loose `public const val`. |

## Architecture

Two halves, because `SeamRoom` already owns the single collection of `seam.incoming`
(`restartIncomingCollect`) and a second collector would violate ADR-034. The host-side relay is a
**branch in the existing dispatch**, never a new collector.

**`:kuilt-core`** — the shared pieces:

- `RelayDest` — a sealed wire type, `Everyone | One(peer)`.
- `RelayEnvelope(origin: PeerId, dest: RelayDest, payload: ByteArray)`, CBOR behind a
  `RoomFramePrefix.Relay` byte.
- `validFirstHop(sender, origin, trusted)`, generic in the id type, lifted verbatim from the rule
  `:kuilt-cluster` already runs: trust `sender ∈ trusted` to carry an already-validated origin,
  otherwise require `origin == sender`.

**`:kuilt-session`** — the wiring:

- `resolveRecipients` (internal) and the relay branch in `SeamRoom`'s dispatch.
- Relay-awareness in `SeamRoom.broadcast` / `sendTo`.
- `ProtocolVersion` → 2, `null` no longer supported.

### The diff is smaller than the issue implies

`RoomChannelSeam` needs **no change**. It already delegates to `room.broadcast` / `room.sendTo`, so
fixing those two methods makes `peers = room.rosterPeers` honest for free — the contract is
**honoured, not restated**.

Internal protocol sends are **never wrapped**: admit, lobby and heartbeat all call `seam.*` directly
(`SeamRoom.kt:951, 1107, 1124, 1235, 1259, 1270, 1280, 1317, 2078, 2398`; `PerPeerSeam.broadcast`
delegates to the raw seam at `:2449`). Host-authority gates like `handleFarewell`'s and
`handlePaused`'s key on the *fabric* sender, so wrapping them would either break those gates or
launder a forgery. This separation is verified, not assumed.

### Send rules — keyed on the destination, not the call

| Condition | `sendTo(p)` | `broadcast()` |
|---|---|---|
| Host role | direct | direct |
| `rosterPeers ⊆ seam.peers` | direct | direct |
| any divergence | relay via host | relay via host |

Two deliberate properties:

- **The host early-returns to the direct path explicitly**, keyed on `role == Host`. Revision 1
  claimed a host always satisfies `rosterPeers ⊆ seam.peers`; that is **false** — a member inside its
  reconnect window stays in the roster while `Seam.peers` has dropped it (#1557/#1614), so a host with
  one partitioned member would have entered the relay branch. It was saved only by `hostPeerId` being
  incidentally `null` on a host, which a plausible tidy-up would break.
- **Once any divergence exists, relay everything** — including to a peer that *is* directly
  reachable. Keying `broadcast` on the roster subset but `sendTo` on the individual peer would, on a
  partial mesh (`hubMesh` spoke), send broadcasts to C via the host (two hops) and unicasts to C
  directly (one hop). The Quilter's ack (`Quilter.kt:717`) could then overtake the delta it acks
  (`:557`). One consistent path per destination-set state is monotone and cheap.

If `hostPeerId` is `null` while the roster diverges, that is an **invariant violation on a joiner**,
not a degrade-quietly case — see [The join window](#the-join-window).

### Receive rules — one resolver, cardinality in the type

```kotlin
public sealed interface Resolved {
    public data object None : Resolved
    public data class Exactly(public val peer: PeerId) : Resolved
    public data class Every(public val peers: Set<PeerId>) : Resolved
}
```

The host resolves once and handles the result in an exhaustive `when` with **no `else`**. Revision 1
returned a `Set<PeerId>` and claimed "a unicast never fans" held structurally. It did not: a set of one
and a set of three have the same type, so the guarantee was a runtime property of a value, invisible to
the compiler and deletable by a later edit. `Exactly` **cannot** hold two peers, and removing a branch
**cannot** compile. For `:kuilt-deal`'s per-recipient card secrets the security property *is*
cardinality, so it belongs in the type.

This does not remove the unicast/broadcast distinction — that distinction is irreducible. It makes it
undeletable, which is the achievable version of the goal.

**The host forwards the original envelope bytes unchanged.** `dest` is only meaningful on the host
hop, so there is no per-recipient re-wrapping: one enqueue, one encoding, and `Everyone` stays
`Everyone` on the wire. A joiner independently checks that `dest` is `Everyone` or `One(selfId)` —
a second, cheap check of the leak boundary at the far end rather than trusting the host's routing.

### Relayed frames may carry application data only

This is the correction that matters most. Revision 1 re-entered the **full** dispatch with a
synthesized sender, and `dispatchIncoming` routes any `0x61` payload to `handleAdmitFrame`. Because
`handleWelcome` is host-authoritative only *after* a host exists (`SeamRoom.kt:1350-1363`), any
admitted joiner could relay a crafted `Welcome` naming itself and capture a co-joiner's `hostPeerId`
while that joiner's was still `null` — then drive every host-authoritative gate on the victim and
permanently break its sends. #1180 hardened this on a flat loom; the four star fabrics were protected
by *topology*, and the relay removes that protection on all of them.

So relayability is an **allow-list**, not a deny-list:

```kotlin
private fun isRelayable(payload: ByteArray): Boolean =
    RoomChannel.isChannelFrame(payload) || RoomFramePrefix.entries.none { it.matches(payload) }
```

A relayed payload is honoured only if it is an explicit channel frame, or claims **no** registered
prefix at all (a plain application frame). That excludes admit, lobby, heartbeat and a nested
`RelayEnvelope` in one predicate — and a *future* frame family is excluded by default rather than
needing to be remembered. A relayed admit frame has no legitimate sender: the admit protocol is by
construction host↔joiner over the direct edge.

### Forwarding leaves the inbound collector

Revision 1 issued up to N−1 suspending `seam.sendTo` calls inside the `seam.incoming.collect` body.
Today that body is effectively non-blocking (`rawIncoming.emit` into a buffer, then a non-suspending
`dispatchIncoming`). Inline sends would let one slow spoke stall the host's entire inbound pipeline —
no `Hello` admitted, no `Resume` answered, no heartbeat pong observed — which then trips the host's own
detectors and manufactures false partitions. Unconditional, unbudgeted relay makes that reachable by
any single admitted member.

Relay forwards therefore go through the **existing** `admitFanOuts` writer (`SeamRoom.kt:1974`,
drained by `runAdmitFanOutWriter` at `:2073`), which already carries the per-recipient send budget and
`ensureActive()` discipline #1781 established. Sharing that writer rather than adding a second one also
keeps order between membership announcements and relayed data: a `Farewell(X)` overtaking relayed data
from X would make the recipient drop X and then reject X's frame at
`RoomChannelSeam.incoming`'s `isAdmitted(sender)` filter.

### What deliberately does not change

Heartbeat detectors stay on **direct edges only**. #1592's route gate reads the *underlying*
`seam.peers`, which relay does not alter — and that is correct: a detector's traffic is per-peer,
continuous and O(N²) in the roster, which is the load #1576 removed. Data is relayed; liveness is not.
Presence for a member with no direct edge still comes from the host's #1557 fan-out. Recorded in the
code, because once the roster is routable this gate reads as over-conservative and invites a "fix".

## Version 2, and the limits of the gate

`ProtocolVersion.CURRENT`/`MIN_SUPPORTED`/`MAX_SUPPORTED` → 2, **and** `isSupported(null)` becomes
`false`. After the bump `null` no longer means "an older peer, tolerable" — it means "predates #1569,
therefore definitionally incapable of relaying", i.e. exactly the population the bump exists to
exclude. A version-less peer is locked out of rooms; that is the intended cost.

**State the limit plainly rather than implying the gate is complete:** the gate lives host-side in
`handleAdmitFrame`, so a **pre-#1569 host** has no gate at all and will admit a v2 joiner and then
black-hole every relayed frame. That case is undefendable from this side and is documented, not fixed.

## Two honest asymmetries

Neither is a blocker; both must be documented rather than papered over.

- **Relay converts a throw into a silent drop — on the second hop.** An unresolvable `dest` resolves
  to `Resolved.None` and is dropped with a debug log; a torn recipient's send is swallowed by
  `runAdmitFanOutWriter`'s best-effort discipline (and `RoomHubSeam.sendTo` already swallows its own
  failures). Nothing is reported back across host → recipient either way. The KDoc must say so —
  revision 1's proposed text promised the opposite ("Reaches that member on every fabric").
  **Revision 3 narrows this**: it originally read "`Room.sendTo` throws `PeerNotConnected` on a mesh
  and is lossy-without-error on a star", which is not what shipped — the *first* hop still throws.
  See [R1](#revision-3-corrections-folded-back-from-implementation).
- **The envelope is unbudgeted.** Revision 1 claimed "the `RELAY_HEADER_BUDGET` reservation is
  honoured". False: that constant is `internal` to `:kuilt-cluster` and invisible here, and there is no
  payload-limit surface at this layer. A payload that fits unrelayed can exceed the fabric's limit once
  wrapped (`FrameTooLargeException` from `:kuilt-stream`). The envelope adds roughly 40–60 bytes and
  nothing reserves it. Follow-up, not in scope.

## The prefix registry's real hazard band

#2007's registry must **not** assert that CBOR major-type-7 (`0xe0`–`0xff`) is the range a serializer
might emit as a leading byte. CBOR text-string headers are `0x60|len`, so a bare top-level string
collides with **every** claimed prefix: length 1 → `0x61` (admit), 3 → `0x63` (channel), 5 → `0x65`
(lobby), 11 → `0x6b` (heartbeat), 18 → `0x72` (relay). The claim is inherited prose
(`RoomChannel.kt:22`); lifting it into a registry KDoc and a green test would convert an unexamined
comment into a certified-false invariant.

The truth to record: `0x60..0x7f` is the real collision band, and the codebase lives with it because
room payloads are **framed**, not bare. The registry's job is single-source-of-truth for the byte space
and distinctness — not a safety proof it cannot make.

Also note for the v2 release: an application payload sent via `Room.broadcast` whose first byte is
`0x72` is now swallowed as a relay frame. That byte was previously legal for app data.

## The join window

`admitPeer` calls `addToRoster` under the lock **before any send** (`SeamRoom.kt:1215-1219`), and the
host-intro that sets a joiner's `hostPeerId` is the **last** of K+1 sequential suspending sends
(`:1250-1270`). So a fresh joiner holds a populated roster with `hostPeerId == null` across every one
of those sends. Two consequences, both in scope:

- It is the capture window for the takeover above.
- A `Quilter` collecting `rosterPeers` fires `onPeersChanged` → `sendFullStateTo(coJoiner)`
  (`Quilter.kt:566`, `:576`) straight into `PeerNotConnected` — #1994's own symptom, transiently
  reintroduced at the moment convergence is being established.

Fix at the source: **set `hostPeerId` from the sender of the first `Welcome`**. Any `Welcome` is by
definition from the host, so this closes the window without depending on send order. (Reordering
`admitPeer` to send the host-intro first would also work but leaves the window open for any host that
skips the intro.)

## Testing

- **Envelope**: round-trip with origin intact; `Everyone` and `One` both survive; garbled body decodes
  to null rather than throwing.
- **Anti-spoof**: a spoke frame whose `origin` names another peer is refused.
- **Allow-list**: a relayed `Welcome`/`Farewell`/heartbeat/nested-relay changes nothing on the
  recipient — specifically, a co-joiner-originated `Welcome` does **not** move the victim's
  `hostPeerId`.
- **Leak boundary**: a unicast for B reaches B and is observed by neither the host nor C.
- **Unresolvable dest**: dropped, never fanned.
- **Done criteria**: two spokes of a `MuxServerLoom` room with a `SeamRoom` host and a `Quilter` each
  — A's local mutation becomes observable in B's `state`.
- **Version gate**: a joiner declaring 1 is refused; `isSupported(null)` is false.
- **Head-of-line**: a relay forward to a stalled spoke does not delay a concurrent `Hello`'s admission.

**Every negative assertion shares a test with its positive control.** Revision 1's two
security-critical tests were `assertTrue(seen.none { … })` and were green *before any relay code
existed* — a bug that dropped everything would have left the leak-boundary test passing. Each test must
assert both that the legitimate frame arrived **and** that the illegitimate one did not, so total
non-delivery turns it red.

Harness discipline: `StandardTestDispatcher`, bounded `advanceTimeBy`/`runCurrent` — **never
`advanceUntilIdle()`** — and a **generous 30 s `runTest` backstop** that is a wedge backstop, never a
tight assertion (it is wall-clock over a virtual-time trajectory, so tightening it measures the host).
The tight fence is the OS-level one on the agent's command.

### The mutation hazard, called out deliberately

The relay branch fires **before** the existing `isAdmittedPeer(sender) -> routeApplicationFrame` arm in
`dispatchIncoming`'s `when`. That is the "an earlier guard un-pins an older test" shape this repo has
hit four times. The mutation set is therefore: the **old** admit guard, `validFirstHop`, the joiner's
`sender != host` check, the `isRelayable` allow-list, and the `dest` membership check — mutated
individually **and in pairs**, since a test can pin `G₁ ∨ G₂` and neither conjunct. Abort on a non-zero
build exit before parsing any results XML: a mutation that fails to compile otherwise reports the
previous run's verdict.

## Non-goals

- Relay payload budgeting / throttling (see the asymmetries above). Follow-up, and only meaningful
  once the relay lands.

### Withdrawn: the `TieredSeam` follow-up already shipped

Revision 1 listed "`TieredSeam.sendTo` silently drops a peer owned by neither tier" as a follow-up. It
is **already fixed** — [#1935](https://github.com/tractat-us/kuilt/issues/1935) is closed and
`TieredSeam.kt:236` now `throw PeerNotConnected(peer)`. Three places still assert the old behaviour and
must be corrected as part of this track's docs task, because each one currently reads as a live hazard:

- `docs/fabric-peer-routing.md:47` — "an id owned by **neither** tier is discarded with no
  `PeerNotConnected`. The worst variant for #1576: even a caller that handles the exception sees
  nothing."
- `startDetector`'s KDoc in `SeamRoom.kt` — "The gate must not be keyed off catching
  `PeerNotConnected`: `TieredSeam.sendTo` silently *drops* …". The gate's design is still right; its
  stated *reason* is now false.
- #1994's own body — "`TieredSeam` is worse — it drops silently rather than throwing."

This is the stale-citation hazard in its usual form: a claim tied to an issue number silently inverts
when that issue is fixed. Verify such a claim before resting an argument on it.
- Migrating `:kuilt-cluster`. **Withdrawn as a goal**, not deferred: with `resolveRecipients` staying
  session-local there is nothing left to migrate beyond `validFirstHop`, which the cluster keeps its
  own call to.

## Docs to update

- `docs/fabric-peer-routing.md` — four fabrics move from "star without relay" to "star with relay",
  filling that table's empty middle category for the first time, plus the liveness carve-out.
- `Seam.peers` KDoc — membership versus routability: a peer in the set is reachable, but not
  necessarily in one hop.
- `Room.broadcast`/`sendTo` KDoc — reaches every member; `broadcast` lossy-without-error, `sendTo`
  reporting its own hop (R1). Plus the **reserved leading bytes**, which until now were documented
  only in `RoomFramePrefix`'s own KDoc, where no consumer reads them: state all five and which two
  are conditional on a classifier narrower than the byte.
- `RoomChannel.CHANNEL_PREFIX` KDoc — stop pointing at "namespace-collision guarantees" the class doc
  now says the registry explicitly cannot make.
- `docs/agent-cookbook.md` entry plus a check that `.claude/skills/kuilt-primitives/SKILL.md` routes
  to it.
- `startDetector`'s KDoc — why the route gate stays narrow after #1994.

## Revision 3 corrections, folded back from implementation

Two places where implementation reached a different answer than this document prescribes. Recorded
here rather than left in the plan, because a design of record that contradicts the code is the stale-
body hazard, and a reader trusts this file over a plan.

### R1 — `Room.sendTo` throws on the first hop; only `broadcast` is lossy-without-error

[Two honest asymmetries](#two-honest-asymmetries) originally concluded that `Room.sendTo` "is
lossy-without-error on a star". What shipped splits the two calls by their contracts:

- **`broadcast` never throws.** A failed relay hop is caught and degrades to a direct
  `seam.broadcast`. The reason is concrete: a `Quilter`'s timer-driven broadcast that threw would
  cancel the coroutine driving anti-entropy — the mechanism that heals the gap once the host returns.
- **`sendTo` throws** when the hop this member must make fails, relayed or not, naming the **host**
  when the host is the hop that failed. An addressed send that vanished silently would re-create
  #1994's own symptom at the send side.
- **Lossy-without-error describes the second hop only.** Host → recipient is a best-effort fan-out.

I5 in the table below stands as written — the promise revision 1 made was wrong. The correction is
that the honest replacement is per-call, not per-fabric.

### R2 — the relay types live in `:kuilt-session`, `internal`, not `:kuilt-core`, public

[Architecture](#architecture) places `RelayDest` and `RelayEnvelope` in `:kuilt-core`, public. Only
`validFirstHop` was lifted; the envelope and the resolver stayed session-local and `internal`. Three
reasons, all checkable:

1. **`:kuilt-core` has no CBOR dependency and this track does not add one.** It declares
   `kotlinx.serialization.core` only, and its charter is "depends on nothing but coroutines +
   serialization". Adding a serialization *format* to the contract module for a type nothing outside
   `:kuilt-session` consumes is a real cost against no benefit. `validFirstHop` is pure and generic
   and needs no dependency at all.
2. **The [Decisions](#decisions) table already said so** — "Reuse: *Lift `validFirstHop` only*", and
   "there is consequently **no** cluster-migration follow-up". The Decisions row is the binding
   statement; the Architecture bullets are the looser text, inherited from revision 1 (which lifted
   the envelope, the rule *and* the resolver). Applying the decision consistently pulls the envelope
   back too, so this resolves a contradiction internal to revision 2 rather than overturning it.
3. **`RoomFramePrefix` must live in `:kuilt-session`.**
   [#2007](https://github.com/tractat-us/kuilt/issues/2007) says verbatim "One registry in
   `:kuilt-session` owning the byte space", and the families it reserves live in `:kuilt-session` and
   `:kuilt-liveness`. An envelope in `:kuilt-core` framed behind `RoomFramePrefix.Relay` would invert
   the dependency arrow — or force the prefix to be split from its codec.

Independently reviewed and upheld. The review confirmed nothing outside `:kuilt-session` plausibly
needs `RelayEnvelope` — `:kuilt-cluster` keeps its own `RaftRelay` dialect and delivers into a
`MutableSharedFlow<RaftEnvelope>` rather than a peer set, and `:kuilt-gossip` decorates below the Room
layer — and that `internal` blocks nothing today, while `internal` → `public` is a non-breaking
one-line change pre-1.0.

So the public surface this track adds is exactly `RoomFramePrefix` and `validFirstHop`.

**The honest weakness, kept in view.** At the Room layer there is no trusted relayer set — every
sender is a spoke — so the session's call is `validFirstHop(sender, origin, trusted = emptySet())`,
which degenerates to `origin == sender`. The lift is justified by the **cluster** keeping its
non-empty `voters` call, not by the session's. A reviewer who holds that a shared function with a
degenerate instantiation is worse than an inline `origin == sender` plus a comment is making a
legitimate call; it is adjacent to the tautology that sank revision 1 (C2), and it should be argued
rather than assumed.

## What revision 1 got wrong

Kept so the reasoning is recoverable and the same mistakes are not re-derived.

| # | Defect | Correction |
|---|---|---|
| C1 | Relayed payloads re-entered the full admit dispatch, letting any admitted member capture a co-joiner's `hostPeerId` on all four target fabrics | `isRelayable` allow-list; `hostPeerId` set from the first `Welcome`'s sender |
| C2 | The de-risking prototype compared `sender in core ‖ origin == sender` against `sender in trusted ‖ origin == sender` — textually the same expression — and pre-wrote its own verdict | Lift `validFirstHop` only; prove the fit by swapping it into `RaftRelayHub` and running the cluster suite |
| C3 | Fan-out suspended inside the single inbound collector | Enqueue on the existing `admitFanOuts` writer |
| C4 | `isSupported(null)` left permissive, re-admitting the exact population the version bump excludes | Carve-out closed; the pre-#1569-host limit documented |
| C5 | Two security tests were `none { … }` assertions, green before the fix existed | Every negative pairs with a positive control in the same test |
| I1 | `Set<PeerId>` erased the cardinality the leak boundary depends on | Sealed `Resolved`; `Exactly` cannot hold two peers |
| I2 | `broadcast` keyed on the roster subset, `sendTo` on the peer — different hop counts to one destination, so an ack could overtake its delta | Relay everything once any divergence exists |
| I3 | "A host always satisfies `rosterPeers ⊆ seam.peers`" — false for a host with a partitioned member | Explicit `role == Host` early return |
| I4 | `hostPeerId == null` silently took a path known not to deliver, on the ordinary join path | Treated as the invariant violation it is; window closed at the source |
| I5 | KDoc promised "reaches that member on every fabric" | Documented as lossy-without-error on a star |
| I6 | Asserted CBOR `0xe0..0xff` as the hazard band; the real band is `0x60..0x7f` and collides with all five prefixes | Registry asserts distinctness only, not a safety property it cannot make |
| I7 | "The `RELAY_HEADER_BUDGET` reservation is honoured" | It is `internal` to `:kuilt-cluster`; nothing reserves anything |
