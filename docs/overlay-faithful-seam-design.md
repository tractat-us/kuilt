# Faithful overlay `Seam` — routed `sendTo` + public dealing

> Status: **Draft / proposed.** Feeds ADR-005 (planning sub-issue [#795](https://github.com/tractat-us/kuilt/issues/795)) and epic [#794](https://github.com/tractat-us/kuilt/issues/794). Consumer driver: fireworks-compose [discussion #2904](https://github.com/tractat-us/fireworks-compose/discussions/2904) / ADR-055.
> Scope: the **single-server hub** case only. Multi-hop / federated routing is explicitly deferred (epic #794 phases 4–5).

## What this is, in plain terms

When a game is hosted (one server, many clients each holding a single connection),
the server is a **hub**: the only relay on the path between any two clients. kuilt's
overlay (`GossipSeam`) already makes a *broadcast* look like it came straight from
its author — it relays the flood and stamps the author as the sender, so the
application never sees the hub in the middle. But a **point-to-point** send
(`sendTo`) is *not* given the same treatment: it only reaches a peer you hold a
physical link to. On a hub that asymmetry means a client cannot reply to another
client, which silently breaks the one thing that makes replicated data reliable —
the gap-repair request.

This design finishes the job: make `sendTo` route through the overlay exactly as
`broadcast` already relays, so the overlay is a **faithful N-peer `Seam`** on a star.
Nothing else has to change — the existing repair machinery starts working the moment
its replies can be delivered. It also records *why* we are **not** modelling traffic
classes in the fabric, and keeps "retreat to full Plumtree" as a documented next step.

## Background — the gap

Three observations from the kuilt source at the current pin:

1. **`GossipSeam` half-masks the topology.** `broadcast` is wrapped in a `GossipFrame`,
   eager-flooded, dedup'd, and on receive delivered with `sender = frame.origin`
   (`GossipSeam.kt:177`) — the hub is invisible. But `sendTo(peer)` is `base.sendTo(peer)`
   verbatim (`GossipSeam.kt`), so it only reaches a *physical* neighbour.

2. **Prompt repair therefore breaks on a star.** Quilter has prompt gap-repair —
   per-sender gap detection → `Resend` — but the `Resend` is addressed to the delta's
   apparent sender (the *origin*, because that is what `GossipSeam` stamped). On a spoke,
   `base.sendTo(origin)` throws `PeerNotConnected`; the request is swallowed; the gap heals
   only on the next anti-entropy round (`reconcileWithRandomPeer`, default 1 min). For
   interactive traffic (chat) that is unacceptable. (`onResend` is additionally guarded to
   the origin replica — `Quilter.kt:761` — so even a relayer that *received* the request
   would drop it; routing the request to the origin sidesteps that.)

3. **There is no third "secret" traffic class to protect.** Hidden information in kuilt is
   communicated **publicly**: `DealSession` (`kuilt-deal`) drives a cryptographically fair
   deal as an op-CRDT *broadcast over the seam*, with per-card visibility controlled by which
   players **strip** their encryption layer — a cryptographic visibility quorum, not transport
   privacy. So a per-recipient secret never needs a private transport path; it is public
   ciphertext that floods and replicates like any other broadcast. (Fireworks' A6 per-seat
   `sendTo` disclosure is a transitional shape that public dealing can replace; it is not a
   constraint the fabric must honour.)

## Design

### Two transport concerns, no classes

The fabric exposes exactly two send verbs, and they **are** the model:

- **`broadcast`** — "everyone may see this." Relayed/flooded across the overlay. Carries
  public game state, presence, chat, and **`kuilt-deal` ciphertext** (public by construction).
- **`sendTo(peer)`** — "exactly this peer." Routed point-to-point, never flooded. Carries raft
  RPCs and CRDT repair traffic (`Resend`/`Ack`/`FullState`).

The fabric stays **uniform and class-agnostic**: it does not know "chat" from "disclosure"
from "raft." Secrecy is a property of the *payload* (`kuilt-deal` encryption), enforced by
cryptography — the strongest possible "structural" guarantee, and one that needs nothing from
the transport. We explicitly **reject** a fabric-level traffic-class system (typed channels /
declared partitions / on-the-wire class tags): it would push peer- and policy-semantics into
the layer whose whole purpose is to mask them.

### Routed `sendTo` (single-hub, one-hop)

`GossipSeam.sendTo(dest, payload)`:

- if `dest` is a direct base neighbour → `base.sendTo(dest, payload)` (today's path);
- otherwise → wrap in a **unicast envelope** `{ dest, src = selfId, ttl }` and forward to the
  hub. The hub, receiving a unicast frame **not** addressed to itself, delivers it to the
  `dest` it holds a link to. Delivery stamps `sender = src`, so the receiving application sees
  a faithful "from `src`."

On a star this is **one hop** each way (`spoke → hub → spoke`); the hub holds every spoke
directly, so there is no routing table and `ttl` is a small loop-cap (≈2), not a real budget.
General multi-hop next-hop routing is **out of scope** (federation phase).

Consequence: **Quilter is unchanged.** Its `Resend`/`Ack`/`FullState` are all `sendTo`, so
they now traverse the relay and the existing prompt repair works. No change to `onResend`, no
relayer-served-repair, no new replicator code.

### Faithful membership

To keep `sendTo(member)` contract-valid (the `Seam` contract throws on a `dest` absent from
`peers`), `GossipSeam.peers` presents the **full overlay membership**, not just physical base
neighbours. The hub disseminates the roster (it already has it from `gameHost` /
`changeMembership`); a spoke's `peers` is then `{ all members }`, and `sendTo(anyMember)` is
valid and routable. This makes the overlay a genuinely faithful N-peer `Seam`.

> Note: full membership is for *addressability*. Quilter's delta-target/GC set (`deltaTargets`)
> stays the **active-view** (a spoke's is just `{hub}`), so a spoke still only owes/acks deltas
> to the hub — the relay carries the rest. Membership and delta-targets are deliberately
> different views (per `gossip-mesh-design.md`).

## Scope boundaries (what this does NOT do)

- **No traffic-class system** in the fabric. Secrecy is `kuilt-deal`'s job.
- **No multi-hop routing.** Single-hub one-hop only; federated next-hop routing is deferred.
- **No Quilter change.** Repair works because `sendTo` routes, not because the replicator changed.
- **No new channel type / no private-channel API** in kuilt. A consumer that wants a
  belt-and-suspenders send-only facade builds it in its own code.

## Retreat to full Plumtree — documented escalation

This design relies on **relayer-routed** repair (route the `Resend` to the origin via the hub).
That is sufficient for a single hub and the near-term partial mesh. Escalate to **full Plumtree**
(lazy-push `IHAVE` digests for *proactive* gap discovery + `GRAFT` of an *alternate* tree edge)
**if and when**:

- the overlay is a true partial mesh at scale (M-server core / k-regular interior), where a
  dropped flood has *alternate* paths worth grafting and anti-entropy latency is too high as the
  sole backstop; **and/or**
- profiling shows reactive routed-repair leaves an unacceptable tail under realistic loss.

Until a trigger fires, routed `sendTo` + anti-entropy backstop is the design. This escalation is
tracked as an explicit next-action against epic #794.

## Testing

All virtual-time: `StandardTestDispatcher` + bounded `advanceTimeBy`/`runCurrent`, seeded RNG,
never `advanceUntilIdle` (gossip timers re-arm).

1. **Prompt repair across the relay (gating).** `gameHost` over a star `meshSeam` + `GossipSeam`
   (hub on `FullFanout`), two client spokes. Inject a dropped flooded delta from spoke A; assert
   spoke B converges via the **routed `Resend`** within a bounded window — *not* at the
   anti-entropy cadence. This is the test that proves the design.
2. **Faithful `sendTo`.** `sendTo(spokeA → spokeB)` is delivered via the hub, stamped
   `sender = spokeA`; `peers` on each spoke contains all members.
3. **No leak-test needed at the fabric.** Secrecy is `kuilt-deal`'s conformance suite; the fabric
   carries only public/encrypted payloads. (The relevant guarantee lives in `kuilt-deal`, by
   construction.)

## Decisions recorded

- **Full-membership `peers`** (faithful Seam) — chosen over physical-only `peers`.
- **Single-hub one-hop routing** — chosen over general multi-hop now (YAGNI; fireworks single-server).
- **No fabric traffic classes** — secrecy via `kuilt-deal`, not transport.
- **Sibling piece:** the broadcast `FullFanout` active-view policy (epic #794 phase 1) is assumed;
  this design depends on it for the happy-path flood but does not redesign it.

## References

- Epic [#794](https://github.com/tractat-us/kuilt/issues/794); planning sub-issue [#795](https://github.com/tractat-us/kuilt/issues/795).
- `kuilt-core` `GossipSeam` / `GossipView` (`docs/gossip-mesh-design.md`); `kuilt-quilter` `Quilter` (repair path).
- `kuilt-deal` `DealSession` / `CommutativeScheme` — public cryptographic dealing.
- fireworks-compose [discussion #2904](https://github.com/tractat-us/fireworks-compose/discussions/2904), ADR-055.
