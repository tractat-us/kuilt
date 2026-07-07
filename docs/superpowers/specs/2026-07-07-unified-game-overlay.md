# The unified game overlay — one Seam, a topology policy, a session mux

> Status: **Draft / design synthesis** for epic [#794](https://github.com/tractat-us/kuilt/issues/794).
> Builds on ADR-005 ([`docs/hosted-game-hub-replication-design.md`](../../hosted-game-hub-replication-design.md))
> and the hosted-bootstrap design ([`docs/gamehosted-bootstrap-design.md`](../../gamehosted-bootstrap-design.md)).
> This doc maps what has **already landed** since the epic was filed, names the three
> abstractions in their current shape, and slices the remainder. No code here — framing only.

## What this is, in plain terms

A group of people playing a game together need their devices to keep talking: moves,
chat, who's here, who left. There are several natural ways to wire the devices up —
everyone talks to everyone; everyone talks through one host; a few servers carry many
games at once, surviving a server crash. Today kuilt supports each of these, but they
grew as **separate code paths**. The idea of this epic is that they are **the same
overlay at different settings**: one shared fabric, plus a *policy* that says who
relays messages to whom, plus a *mux* so many games share one set of connections.
Get that right and the same game code runs unchanged from "one phone hosts the
game" all the way to "three servers carry a thousand games and one can die".

The application never rewrites. It says `gameHost` / `gameJoin` / `gameNode` and gets
a `GameSession`; which wiring carries it is chosen underneath, like picking a route
without changing the destination.

## Recognition: most of it has already shipped

The epic was filed 2026-06-24. Since then, two of its five implementation phases have
landed, and a sibling effort (#948, Room-per-game) landed most of a third. Name the
role, reveal the primitive under it:

| Role in the unified overlay | The primitive that already ships |
|---|---|
| "Who do I eagerly relay to?" — the **active-view policy** | `ActiveViewPolicy` (`:kuilt-gossip`) — `FullFanout` (hub star) and `RandomKRegular` (partial mesh) landed in #829 |
| The relay engine that runs over *any* active view | `GossipSeam` — eager-flood + dedup + TTL + anti-entropy backstop; `sendTo` passes through unwrapped (the tested unicast invariant) |
| "Accept whoever connects and run a game over them" — the **hosted bootstrap** | `ConnectionSource` → `hostedOverlay` → `gameHosted` (#834/#837/#838), with principal attestation (#1286) |
| "Many logical sessions over one socket" — the **session mux**, client side | `MuxClientLoom` — one base weave, N named channels, per-channel close, one-reconnect-heals-all |
| Same, server side, with isolation by construction | `MuxServerLoom` + `RoomHubSeam` (#970) — per-room fanout domains; a non-member is structurally never in the fanout list |
| The wire envelope that names a session | `NamedFrame` — the `[len][name]` header `NamedMux`/`MuxServerLoom` already route on |
| The **two-tier** server topology (voter core + learner periphery) | `ServerCluster` / `VoterMesh` / `LearnerRouter` / `ClusterClient` (`:kuilt-cluster`) — but as a bespoke path, not a policy instance |
| Consensus, membership, app channels | `:kuilt-raft`, `gameHost` admission, `GameSession.appChannel` (`NamedMux` under mux tag 3) — identical across all bootstrap paths |

**The reduction:** the epic's terrifying list — three overlay implementations, a relay
bypass, a session mux, federated routing — collapses to: *generalize one fun-interface,
compose two things that already exist, and re-express `:kuilt-cluster`'s interior as a
policy instance.* The genuinely new code is the federated router; everything else is
composition and deletion.

## The progression, as one picture

```
 single-host star            partial mesh                federated two-tier
 (FullFanout)                (RandomKRegular)            (TwoTier — to build)

      C   C                    P ─── P                    S1 ── S2 ── S3     voter core
       \ /                    /│ ╲  ╱│                    /│      │     │╲    (K_m, real seams)
   C ── H ── C               P │  ╳  │ P                 C C      C     C C   learner periphery
       / \                    ╲│ ╱  ╲│                   (each client → its server;
      C   C                    P ─── P                    games span servers)

 same gameHost/gameJoin/gameNode · same Quilter/Raft/app channels · policy differs
```

## Abstraction A — the topology policy: from *size* to *shape*

What landed in Phase 1 is deliberately minimal — a size function:

```kotlin
public fun interface ActiveViewPolicy {
    public fun activeViewSize(rosterSize: Int): Int   // FullFanout = N-1; RandomKRegular = k(N)
}
```

*Which* peers fill the view is still `GossipView`'s hardcoded random sample. That is
fine for the two shipped policies, because both are **isotropic** — any k peers are as
good as any other k. `TwoTier` is not: a client's active view is *structurally* its
attachment server; a server's is *the other servers plus its local clients*. Federation
therefore forces the policy from a size to a **shape** (the #671 refactor):

```kotlin
public interface TopologyPolicy {                     // working name; subsumes ActiveViewPolicy
    /** The peers this node eager-floods to and GCs against. */
    public fun activeView(self: PeerId, roster: Set<PeerId>): Set<PeerId>
    /** The pool anti-entropy samples (usually the full roster; TwoTier: tier-local). */
    public fun antiEntropyPool(self: PeerId, roster: Set<PeerId>): Set<PeerId>
}
```

- `FullFanout` — `roster - self`. The hub star; already proven by `hostedOverlay`.
- `RandomKRegular(random)` — a seeded k-out sample, re-drawn on roster churn. Today's
  emergent mesh, made explicit. (The randomness moves *into* the policy instance,
  which is what lets `GossipView` stop owning selection.)
- `TwoTier(core: Set<PeerId>, attachment: (PeerId) -> PeerId)` — clients: `{attachment(self)}`;
  servers: `core - self + localClients`. The federated interior.

`GossipSeam`'s relay, dedup, and anti-entropy run over any of these unchanged. Raft
traffic (`sendTo`) passes through untouched, exactly as it does today — one fabric,
and the policy only ever governs *broadcast dissemination*, never unicast.

**Open design question A (for Iain):** is `TwoTier` honestly an instance of the same
policy interface, or is it a *composition* (a `GossipSeam` per tier, bridged at each
server)? The single-interface version is prettier; the per-tier version may match the
GC/anti-entropy semantics better, because a server's Quilter peers-set differs by tier.
The interface above is my proposal; the alternative is `TwoTier` as a decorator that
owns two policy instances.

## Abstraction B — the session mux: mostly landed, one composition missing

The epic asked for "a `(gameId)` envelope over a shared fabric; `overlay(gameId,
members)` returns a `Seam`". Since then #948 landed exactly this in the *room*
dimension:

- **Client:** `MuxClientLoom` weaves one socket; `join("table-7")` and `join("lobby")`
  are named channels over it. The channel name **is** the `gameId` envelope, and
  `NamedFrame` **is** the routing header — the server routes on it without demuxing
  channel contents, which settles #795's "outer mux vs routing header" lean: the
  routing header won, and it already exists.
- **Server:** `MuxServerLoom.host(Pattern("table-7"))` returns a `RoomHubSeam` whose
  fanout is *only* the connections admitted to that room (via a required
  `RoomAuthorizer`). Isolation is by construction — the security property fireworks'
  per-seat masking needs.

What has **not** landed is the composition: running a **game cluster per room**. Today
`gameHosted` runs one game over one `hostedOverlay` (one `ConnectionSource`, one
`GossipSeam`). The missing piece is `gameHosted`-per-`RoomHubSeam` — N concurrent
`GameSession`s on one server, one shared connection set, each room its own Raft
cluster, presence channel, and app-channel namespace. Two concrete sub-gaps:

1. **The relay interior of a `RoomHubSeam`.** `hostedOverlay` gets spoke→spokes
   re-flooding from `GossipSeam`; `RoomHubSeam` is a bare fanout primitive. The rooms
   path needs the same relay semantics (wrap each room in the policy layer, or teach
   the room hub the relay directly). Open regression #1309 — a one-shot broadcast not
   relayed to passive spokes on the star path — is evidence this composition is
   currently under-pinned by tests and must be nailed as part of this slice, not after.
2. **Roster scoping.** A room's `Seam.peers` must be the room's members, not the
   connection set — `RoomHubSeam` already does this; the slice is proving
   `gameHost`'s admission and liveness loops run correctly against it.

## Abstraction C — the federated router: the genuinely new code

`:kuilt-cluster` already has the two-tier *shape* (`docs/architecture.md` §Server-cluster
topology) but as a bespoke path with three deliberate MVP shortcuts:

- `VoterMesh` is **in-process only** — voters talk over `Channel`s, so "3 servers" means
  3 nodes in one JVM. Federation needs a real inter-server network seam (voter↔voter
  over WebSocket/TCP — which `SeamRaftTransport` over a `meshSeam` of server-to-server
  connections already knows how to be).
- `LearnerRouter` routes by `NodeId` within one cluster; federation routes
  `(gameId, user) → server` so a game's members can attach to *different* servers.
  The router is the interior of the `TwoTier` policy: a frame for game G from a client
  on S1 crosses the core to S2 and S3, which relay to *their* local members of G.
- `ServerCluster` bypasses the game stack entirely (raw `raftNode` + per-connection
  `Room`s + a relay loop). End state: it becomes `gameHosted`-per-room over a `TwoTier`
  policy, and the bypass is deleted.

**Why voters = servers** (recorded from the epic): durable game state lives in the
raft core, so 3 servers survive one death; clients are reattachable learners — a
client whose entry server dies reconnects to another and rejoins its game, because
state was never only on the client. This is the one capability the single-host star
cannot give, and the entire reason federation exists.

## The honest seam: a policy can virtualize dissemination, not authority

Everything above sells "topology is just a policy". Here is the one place that is
allowed to leak, and why.

The policy decides **who relays a broadcast**. It cannot decide **where the voters
sit** or **whom you can reach point-to-point** — those are facts about the world, not
dissemination settings:

- **Authority placement.** Quorum and durable state must live on real machines.
  Single-host: the host device is the singleton-then-grown voter set. Federated: the
  *servers* vote and every player is a learner. Switching `FullFanout → TwoTier` is
  therefore not a pure policy swap — the game's consensus **moves**. `gameHost` run
  by a phone and `gameHost` run by a server core produce clusters with different
  fault-tolerance and different trust. The bootstrap entry point (`gameHosted` vs the
  federated equivalent) is where that choice surfaces, deliberately: the epic's "same
  code on every topology" claim holds for the *session-consuming* code
  (`TurnSequencer`, app channels, Quilter), not for who calls the host-side bootstrap.
- **Unicast reachability, and the leak boundary.** ADR-005's tested invariant —
  `sendTo` is never relayed, only `broadcast` floods — is what keeps per-recipient
  secrets (fireworks' per-seat disclosure) safe on the transport guarantee alone.
  Federation needs a frame to *cross the core* (a learner's propose to the leader; the
  leader's AppendEntries to a learner on another server). The router must make unicast
  **reachable without making it broadcast**: strictly single-addressee routing,
  `spoke → server → core → server → spoke`, never fanned. That is a constraint on the
  router's design, inherited from the leak boundary — not a feature to add later.

So the honest statement is: *dissemination scales by policy; authority and reachability
scale by the router, and the router carries the leak-boundary obligation with it.*

## Consumer requirements already on the record (fireworks, from #794's comment)

1. **Injectable consensus seam on every bootstrap** — a `gameHost`-equivalent accepting
   a pre-built node (or factory), so `FakeRaftNode(Leader)` tests survive adoption.
   Hard acceptance criterion downstream; today `gameHost` constructs its node
   internally (`RaftConfig(expectVirtualTime = true)` is the only virtual-time path).
2. **Per-recipient secret traffic must never be flooded** — held today by the unicast
   invariant and its gating tests; every slice below re-runs that gate. ADR-005
   explicitly **rejected** declared channel partitions (floodable vs never-floodable
   channels) in favour of the single verb rule; revisiting that is decision C below.
3. **Relay-served `Resend`** — Quilter's prompt gap-repair is origin-only and cannot
   cross a relay; recorded as escalation 1 in ADR-005 with explicit triggers. Stays an
   escalation, not a slice.

## Slices (ordered, dispatchable)

Each slice is independently valuable, lands with virtual-time tests
(`StandardTestDispatcher`, bounded advance, seeded RNG, harness rules per CLAUDE.md),
and — after the first — deletes something.

| # | Slice | Contents | Deletes / proves |
|---|-------|----------|------------------|
| 0 | **Star-relay regression gate** | Fix #1309 (one-shot broadcast not relayed to passive spokes); add the missing relay-conformance tests for the star composition | Proves the Phase-1 baseline is actually solid before building on it |
| 1 | **Policy: size → shape** | `TopologyPolicy` (activeView + antiEntropyPool as sets); re-express `FullFanout`/`RandomKRegular`; `GossipView` consumes the policy instead of owning random selection (#671) | Deletes `GossipView`'s hardcoded k-out sampling; behaviour-preserving (seeded-RNG golden tests) |
| 2 | **Injectable consensus seam** | `gameHost`/`gameHosted`/`gameNode` variants accepting a pre-built `RaftNode`/factory | Unblocks fireworks' virtual-time adoption (their hard criterion) |
| 3 | **Game-per-room** | `gameHosted` over `MuxServerLoom`/`RoomHubSeam`: N isolated `GameSession`s on one server, one connection set; room-scoped relay semantics; client via `MuxClientLoom` | Proves the session mux end-to-end; the "N concurrent games, isolated" epic item |
| 4 | **Inter-server seam** | `VoterMesh` over real `Seam`s (server↔server `meshSeam` + `SeamRaftTransport`); M=3 over loopback WS in a gated test | Deletes the in-process-only limitation of the voter core |
| 5 | **TwoTier + federated router** | `TwoTier` policy; `(gameId, user) → server` directory; cross-core routed unicast (strictly single-addressee); client failover to a surviving server rejoining its game | The multi-server failover story; generalizes `LearnerRouter` |
| 6 | **Unify + delete** | `ServerCluster` re-expressed as game-per-room over `TwoTier`; retire the raw-`raftNode` relay bypass and per-connection `Room` path | The point: N special cases → one Seam + policy + mux. Done-when matrix test: same game code on (1) in-memory mesh, (2) single-server hub, (3) 3-server federation with one-server failover |

Slices 0–3 are near-term and each fireworks-adoptable; 4–6 are the federation arc.
Slice 2 is trivially parallel to everything. Slice 5 is the only one with real design
risk and should get its own planning sub-issue once decisions A–C below are resolved.

## The open decisions (what this doc wants from Iain)

**A. Policy shape.** One `TopologyPolicy` interface covering isotropic *and* structural
topologies (proposed above), or `TwoTier` as a per-tier composition of simpler
policies? Decides slice 1's target and how much of `GossipView` survives.

**B. Consensus placement in federation.** Per-game Raft cluster among the servers
(every game gets its own log, voters = all M servers — simplest, matches
`gameHosted`-per-room directly), or per-game shard (hash `gameId` → k of M)? And is
the *client-side host* (`gameHost` on a phone) still a supported topology once a
server core exists, or does federated mode always move the leader server-side? #795
leaned "all-servers-vote first, shard later" — I agree, but the phone-host question
decides whether slice 6 can truly delete the single-host paths or must keep both
authority placements first-class. My read: keep both — they serve different products
(LAN/offline vs hosted) — and let the *bootstrap entry point* be the explicit switch.

**C. Routed unicast surface.** Does cross-core routing stay **internal** to the Raft
transport layer (only consensus envelopes cross the core; app-level `sendTo` remains
hub-local — narrowest, preserves the leak boundary trivially), or does the federated
overlay present a faithful N-peer `Seam` where any member can `sendTo` any member
through the core? ADR-005 chose "honestly hub-centric" for the star; federation
re-poses the question at larger scale. Narrow first is my recommendation — widen only
when a consumer needs cross-server app unicast, and then under the single-addressee
guard.

## References

- Epic [#794](https://github.com/tractat-us/kuilt/issues/794) · planning [#795](https://github.com/tractat-us/kuilt/issues/795) (closed by ADR-005) · bootstrap [#831](https://github.com/tractat-us/kuilt/issues/831) · rooms [#948](https://github.com/tractat-us/kuilt/issues/948) · relay regression [#1309](https://github.com/tractat-us/kuilt/issues/1309)
- ADR-005: [`docs/hosted-game-hub-replication-design.md`](../../hosted-game-hub-replication-design.md) — the star baseline, unicast invariant, escalation ladder
- [`docs/gamehosted-bootstrap-design.md`](../../gamehosted-bootstrap-design.md) — `ConnectionSource`/`hostedOverlay`/`gameHosted`
- [`docs/gossip-mesh-design.md`](../../gossip-mesh-design.md) — the partial-mesh overlay
- [`docs/architecture.md`](../../architecture.md) §Server-cluster topology — the two-tier model and attachment degree
