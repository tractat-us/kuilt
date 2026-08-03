# Star relay: making a room's roster genuinely routable

Design for [#1994](https://github.com/tractat-us/kuilt/issues/1994). Written 2026-08-03.

## The problem, restated

`RoomChannelSeam` publishes the **roster** as `Seam.peers` (`RoomChannel.kt:133`) but routes
`sendTo` through the **transport** (`:154`). On a star fabric those are different sets, so a
`Quilter` over a room channel targets peers it can never address.

Two corrections to the issue's own analysis, both material:

1. **`broadcast` does not "work".** The issue's table marks it surviving. It does not deliver.
   `MuxServerLoom.readLoop` spools a spoke's frame into the *host's* `incoming` and stops — for
   `broadcast` exactly as for `sendTo`. Already pinned by #1588's
   `spokeFramesReachOnlyTheHostNeverAnotherSpoke`. So **no** frame a joiner sends reaches a
   co-joiner, and `Room.broadcast`'s own KDoc ("to all other admitted members") is broken too —
   not just `Seam.peers`. This rules out the issue's option 2 as a standalone fix: intersecting
   `peers` with the transport makes the Quilter honest but still non-convergent, because there is
   no host re-broadcast to depend on.

2. **kuilt already solved this — the mechanism is just private to `:kuilt-cluster`.**

   | Existing piece | What it already gets right |
   |---|---|
   | `RaftRelay(origin, dest, bytes)` + `RaftRelayHub` | spoke→hub→named target with **origin never re-stamped**; `validFirstHop` rejects a spoke forging another's id; an illegal `dest` is dropped and **never re-forwarded** |
   | `RoutedRaftTransport` / `playerRelayTransport` | the spoke side, reserving `RELAY_HEADER_BUDGET = 256` out of the inner frame limit |
   | `RoutedUnicastRouter` + `RoutingEnvelope` | the second hop, with an explicit leak boundary: a unicast must **never** degrade into a broadcast |

   Decisively: `ClusterClient` rides `Room.channel("raft")` — the very `RoomChannelSeam` #1994
   calls broken — and works, because the cluster layer *never trusts `Seam.peers` for routing*. It
   installs `NoPeerRaftTransport` so every send is forced through the relay dialect.

So the defect is **not** "kuilt lacks relay". It is that the relay dialect is unavailable outside
`:kuilt-cluster`, so a `Quilter` over a room channel takes the roster at face value — the one thing
the cluster layer learned not to do. (`Quilter.kt:502` already says "or a relayed delivery was
dropped": it anticipated a relay it never got.)

This is therefore a **lift-and-generalise**, not an invention.

## Decisions

| Question | Decision |
|---|---|
| Honesty vs convergence | **Relay.** Make the star behave like a mesh at the Room layer, so the existing contracts become true. Only option meeting the issue's done criteria. |
| Layer | **Session** (`SeamRoom` host). Covers all four no-route fabrics (`MuxServerLoom` hub, `hubMesh` spoke, `:kuilt-multipeer`, `:kuilt-nearby`) because each gets a `SeamRoom` host; reuses the roster, admit gate and `ProtocolVersion` already there. A core-layer relay would cover one fabric and would have to push the roster down into the fabric, re-implementing the admit protocol a layer lower. |
| Policy | **Unconditional.** Every `SeamRoom` in Host role relays. No knob; the contract is simply true. |
| Old hosts | **Bump `ProtocolVersion`; refuse the join.** Relay changes what a room means, so `MIN_SUPPORTED` moves to 2. A mixed pair fails loudly at admit rather than black-holing for 90 s — which is exactly how #1994 was found. The gate already exists in `handleAdmitFrame`. |
| Reuse | **Lift to a shared primitive; do not *merge* the `:kuilt-cluster` migration, but *prototype* it in this track.** Migrating a shipped consensus path (learner forwarding, cross-relay failover) at the same time as adding a feature is the change shape this repo's spec-conformance rule warns about — so the migration does not land here. But deferring it *blind* is how kuilt would end up with two permanently divergent relay dialects, so the track carries a throwaway prototype that proves the lifted primitive genuinely subsumes `RaftRelay`/`RaftRelayHub`, gating the primitive's shape before the relay ships on it. |
| Frame prefix | **#2007's registry lands first, as slice 0.** The relay claims `0x72` *from* the registry rather than adding a sixth loose `public const val`. The cheapest moment to make the byte space managed is the moment an entry is added; doing it afterwards means editing the relay's framing twice. |

## Architecture

Two halves, because `SeamRoom` already owns the single collection of `seam.incoming`
(`restartIncomingCollect`) and a second collector would violate the ADR-034 single-collection
contract. The host-side relay is therefore a **branch in the existing dispatch**, never a new
collector.

**`:kuilt-core`** — the reusable primitive:

- `RelayEnvelope(origin: PeerId, dest: PeerId?, payload: ByteArray)`, CBOR, frame prefix `0x72`
  ('r'). `dest == null` means *every other member*. The prefix is free: `0x61` admit, `0x63`
  channel, `0x65` lobby, `0x6b` heartbeat are taken.
- `validFirstHop(sender, origin, hub)`, generalised from the cluster rule: trust `sender == hub` to
  carry an already-validated origin; otherwise require `origin == sender`.

**`:kuilt-session`** — the wiring:

- A relay branch in `SeamRoom`'s frame dispatch.
- Relay-awareness in `SeamRoom.broadcast` / `SeamRoom.sendTo`.
- `ProtocolVersion.CURRENT`/`MIN_SUPPORTED`/`MAX_SUPPORTED` → 2.

### The diff is smaller than the issue implies

`RoomChannelSeam` needs **no change**. It already delegates to `room.broadcast` / `room.sendTo`, so
fixing those two methods makes `peers = room.rosterPeers` honest for free — the contract is
**honoured, not restated**, satisfying the issue's done criteria on that clause.

Internal protocol sends (admit, heartbeat, lobby) call `seam.*` directly and are **never wrapped**:
host-authority gates such as `handleFarewell`'s and `handlePaused`'s key on the *fabric* sender, and
wrapping them would either break those gates or launder a forgery through the host.

### Send rules (spoke side)

| Call | Condition | Action |
|---|---|---|
| `sendTo(p)` | `p ∈ seam.peers` | direct `seam.sendTo(p, inner)` |
| `sendTo(p)` | `p ∉ seam.peers` | `seam.sendTo(host, Relay(origin = self, dest = p))` |
| `broadcast()` | `rosterPeers ⊆ seam.peers` | direct `seam.broadcast(inner)` |
| `broadcast()` | `rosterPeers ⊄ seam.peers` | `seam.sendTo(host, Relay(origin = self, dest = null))` |

Roster/transport divergence is the trigger, so a mesh fabric keeps the direct path at zero cost and
with no wire change — as does a 2-peer room, where `rosterPeers` is `{self, host}`.

These rules are **joiner-side only in effect**. A host is directly connected to every admitted
member, so `rosterPeers ⊆ seam.peers` always holds there and both calls take the direct path
unchanged. The host's role in the relay is *forwarding*, below — never wrapping.

### Receive rules — one resolver, not two branches

The host does **not** branch on `dest == null`. It resolves a recipient set and delivers to it:

```
recipients = resolve(dest) − origin        where  resolve(null) = allMembers
                                                 resolve(p)    = {p}

for each r in recipients:
    r == self  ->  deliver into my own incoming
    else       ->  seam.sendTo(r, inner)
```

One code path. "A unicast never fans" then holds **structurally** — `resolve(p)` has exactly one
element — rather than resting on a guard a later reader can delete. A `dest` naming no member
resolves to the empty set: dropped, never re-forwarded, matching `RaftRelayHub`.

Joiner side: accept a relay frame **only** when the fabric sender is the identified host, then
re-dispatch the inner frame as `swatch.copy(sender = relay.origin)` (`Swatch.copy(sender)` is
already public). Emitting the *unwrapped* swatch into `rawIncoming` is what keeps
`RoomChannelSeam.incoming`'s `room.isAdmitted(swatch.sender)` filter meaningful; the wrapped frame
is never emitted.

### What deliberately does not change

Heartbeat detectors stay on **direct edges only**. #1592's route gate reads the *underlying*
`seam.peers`, which relay does not alter — and that is correct: relaying O(N²) pings through the hub
is precisely what #1576 avoided. Presence for unroutable members continues to come from the host's
authoritative #1557 fan-out. This is recorded in the code, because the gate will otherwise read as
over-conservative once the roster is routable and invite a "fix".

## Testing

- **Envelope**: round-trip; unknown-field tolerance.
- **Anti-spoof**: a spoke frame whose `origin` names another peer is refused (`validFirstHop`).
- **Leak boundary**: a unicast for spoke B never enters the host's own `incoming`, and never
  reaches spoke C.
- **Unknown dest**: dropped, never re-forwarded, never fanned.
- **Done criteria**: two spokes of a `MuxServerLoom` room with a `SeamRoom` host and a `Quilter`
  each — A's local mutation becomes observable in B's `state`.
- **Version gate**: a joiner declaring version 1 is refused with `ProtocolMismatch`.

Harness discipline: `StandardTestDispatcher`, bounded `await`/`settle` (never
`advanceUntilIdle()` — relay and heartbeat timers re-arm), and a **generous 30 s `runTest`
backstop**, which is a wedge backstop and never a tight assertion (it is wall-clock over a
virtual-time trajectory, so tightening it measures the host). The **tight** fence is the OS-level
one on the agent's command: `timeout 90 ./gradlew :<module>:test --tests "<one>"`.

### The mutation hazard, called out deliberately

The relay branch fires **before** the existing `isAdmittedPeer(sender) -> routeApplicationFrame` arm
in `dispatchIncoming`'s `when`. That is the "an earlier guard un-pins an older test" shape this repo
has now hit four times. So the plan must:

- Mutate the **old** admit guard and confirm its pins still go red — not merely that the new tests
  pass.
- Mutate **pairs**, since a test can pin `G_new ∨ G_old` and neither conjunct.
- Abort on a non-zero build exit before parsing any results XML (a mutation that fails to compile
  otherwise reports the *previous* mutation's verdict).

## Non-goals

Filed as follow-ups rather than folded in:

- `TieredSeam.sendTo` silently drops a peer owned by neither tier — the worst #1576 variant, and the
  one an exception-keyed check cannot see.
- **Landing** the `:kuilt-cluster` migration. Note this is a non-goal only for *merging*: the track
  does prototype it (see Decisions), because a blind deferral is what would leave two divergent
  dialects. The prototype's job is to gate the primitive's shape, then be thrown away.
- Relay rate limiting / bandwidth caps. The `RELAY_HEADER_BUDGET` reservation is honoured; throttling
  is not in scope.

## Docs to update

- `docs/fabric-peer-routing.md` — four fabrics move from "star without relay" to "star with relay",
  filling that table's currently-empty middle category for the first time.
- `Seam.peers` and `Room.broadcast`/`sendTo` KDoc — the contract is now honoured on a star.
- `docs/agent-cookbook.md` entry plus a check that `.claude/skills/kuilt-primitives/SKILL.md` still
  routes to it — a new primitive with no cookbook entry is a broken build by this repo's rule.

## For the Fable review

Two things to challenge hardest:

1. **The recipient-set resolver.** Confirm it genuinely removes the unicast/broadcast distinction
   rather than hiding it, and that the leak boundary is structural under it. Avoiding the need for
   the distinction at all is the goal; if the resolver does not achieve that, say what would.
2. **Whether the lift is the right seam.** The cluster dialect keys on `NodeId` and reads live
   voters per frame; the session dialect keys on `PeerId` and trusts the admit roster. If those are
   not the same primitive underneath, the "lift" is a coincidence of shape and should be two things.
