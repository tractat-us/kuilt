# Design — server-cluster topology with client fan-out (epic #485)

_2026-06-16. Epic: #485. Builds on propose-forwarding (#483/#486) and exactly-once
dedup (#484/#493)._

## Goal

Support an **asymmetric server/client topology** on top of peer-symmetric
`kuilt-raft`: a small voter set of **servers** (1/3/5 — the fault-tolerance dial)
plus many **learner-clients** that submit actions by forwarding and observe state
by replication. Fault tolerance (voter count) is decoupled from client count.

Each client connects to **one** server entry point. If that link tears, the client
**round-robins** to another server and the cluster transparently routes its proposal
to the current leader. The client never needs to know who the leader is.

This is the ZooKeeper-observer / etcd-client / Consul model: a client talks to an
arbitrary member, which forwards writes to the leader.

## What is already built (the foundation #485 stands on)

The consensus layer is **done** — #485 is a topology + client-reconnect assembly
problem, not a Raft problem:

| Capability | Surface | Status |
|---|---|---|
| Non-voting replicas | `ClusterConfig.learners` — receive replication, never vote/lead | ✅ |
| Propose from any node | `RaftNode.propose()` forwards follower/candidate/**learner** → leader (§8) | ✅ #486 |
| Safe retry after reconnect | `DedupKey` + `ClientSessionTable` exactly-once dedup | ✅ #493 |
| Dynamic membership | `RaftNode.changeMembership(ClusterConfig)`; **learner-set-only change skips joint consensus** (`MembershipState`, only voter changes need the joint transition) | ✅ |
| Reconnect machinery | `kuilt-session`: `ResumeToken` / `JoinerReconnectController` / `RoomId` (survives leader change by design) | ✅ |
| Relay host | `KtorRoomHost` (JVM/Android) — every connected peer shares one `Room`/`Seam` | ✅ |

The remaining work is **glue**: get learner-clients onto a cluster Seam, and
round-robin them across server endpoints when an entry-server tears.

## Topology & connectivity model

The cluster is a **two-tier overlay** — a densely-connected core plus a sparse
periphery — and naming it that way keeps "good connectivity" precise.

```
   voter core — complete graph K_m  (consensus lives ENTIRELY here)
   ┌─────────────────────────────┐
   │     S1 ──── S2 ──── S3       │   m = 1/3/5  (fault-tolerance dial)
   │      \      │      /         │   every voter pair linked; leader replicates to all
   └───────\─────┼─────/──────────┘
            \    │    /  ╲
   d=1 ──────C   │   C   C────── d=2   ← attachment degree = the "cross-link" axis
  (star leaf,    │              (multi-homed: 2 disjoint paths to the core)
   slice-1 dflt) C
                 learner periphery — clients forward-to-propose, replicate-to-observe
```

- **Voter core — a complete graph `K_m`.** The `m` servers (m = 1/3/5) each hold a
  link to every other voter. Election, quorum, and commitment live here and nowhere
  else. "Fully connected" is a requirement of the **core only**.
- **Learner periphery — sparse.** Many clients, each attached to the core by one or
  more server links, never voting and never counting toward quorum. A complete graph
  over *clients* would be the O(n²) cost the two-tier split exists to avoid.

**Attachment degree `d` — the cross-link dial.** Each client attaches to the core
with degree `d` = the number of distinct servers it holds a link to. Formally this
is the client's **vertex connectivity** to the core: `d` vertex-disjoint attachment
paths tolerate `d − 1` server removals.

- **`d = 1` — star leaf.** One live link plus a static list of other endpoints to
  **round-robin** to on tear. Slice 1's default; survives 0 *simultaneous* server
  failures without a reconnect (it must fail over).
- **`d > 1` — multi-homed leaf (cross-links).** `d` redundant links, so the client
  rides out `d − 1` server failures with no reconnect at all. An **optional** later
  dial (lower failover latency), not committed in slice 1.

**Safety is topology-independent for clients.** Raft safety depends only on the
voter quorum, so a client's attachment degree — even momentarily `0` during failover
— can never threaten consistency. Client connectivity is purely an **availability /
forward-latency** dial, not a correctness one. This is what makes the cross-link
decision safe to defer: under-provisioning it costs reconnect latency, never wrong
state.

The two transport shapes in D1 are two ways to realise this periphery: the
relay-room attaches leaves through a shared hub (logical all-to-all over a sparse
physical star), the point-to-point star gives each leaf a direct `d = 1` link the
server relays inward.

## Decisions

### D1 — Both transport shapes are in scope, relay-room first

1. **Relay-room (hub-attached periphery) — slice 1.** Servers run a relay
   (`KtorRoomHost`); a leaf connecting to any one relay endpoint is logically a peer
   on the **shared** cluster Seam, so propose-forwarding already routes it to the
   leader. Logical all-to-all over a sparse physical star — needs only round-robin
   reconnect glue. *Lands first.*
2. **Point-to-point star (direct `d = 1` attachment) — slice 2.** Each client holds
   a 2-peer Seam to exactly one server; the voter core meshes separately. The leaf's
   Seam can't address the leader, so the attached server must **relay** the leaf's
   raft messages into the core — a fabric-level multi-hop relay we don't have yet.
   Materially bigger; gets its own sub-spec.

### D2 — Prove in examples first; extract `:kuilt-cluster` only once the shape is proven

No new module up front. Slice 1 needs only a minimal round-robin endpoint-reconnect
helper in `:kuilt-session`; the server/client assembly is demonstrated as an
`examples/` integration test (alongside the existing tic-tac-toe/chat examples). A
`:kuilt-cluster` facade (`ServerCluster` / `ClusterClient`) is extracted **after**
slice 1 (and ideally slice 2) prove the shape — matching the pre-1.0 low-ceremony
posture. Premature API is the risk we're avoiding.

### D3 — Client onboarding is an add-learner membership change

A learner only receives replication if it is in `ClusterConfig.learners`, so a
client joining the cluster is a `changeMembership` that adds it as a learner.
Because a **learner-set-only change skips joint consensus**, each join/leave is a
cheap simple config entry — not a joint transition. The leader owns these changes;
a client's join request rides the same forward path as a propose.

**Open scaling question (slice 1, non-blocking):** every join/leave appends a
config entry to the replicated log. For "many, ephemeral" clients this is churn on
the shared log. v1 accepts it (mirrors the dedup-table "stable ids" guidance —
reuse a stable client `NodeId` so re-join is idempotent). A cap / lease-based
learner GC is a post-slice-1 follow-up, paired conceptually with dedup GC v2 (#495).

## Open design questions (to resolve during slice work, not blockers)

- **O1 — learner churn / log growth (D3 above).** Stable-id reuse for v1; lease/GC later.
- **O2 — endpoint discovery.** Slice 1 takes a static list of server endpoints
  (constructor arg). Dynamic discovery (mDNS, a gossiped roster) is out of scope.
- **O3 — round-robin policy.** Plain rotation vs. randomized vs. health-weighted.
  Start with deterministic rotation over the static list; make the selector injectable.
- **O4 — resume vs. fresh-join on round-robin.** When a client fails over to a
  *different* server, the `ResumeToken` (keyed on `RoomId`, leader-identity-free)
  should let it resume against the shared room. Confirm the token survives an
  entry-server change, not just a leader change. If not, failover degrades to a
  fresh join (still correct, costs a re-snapshot).

## Slices & sub-issues

Per EPIC convention the **first sub-issue is the planning sub-issue**, closed by the
PR that lands this spec + the plan doc, *before* any worker dispatch. Closing
keywords go on that planning sub-issue only; the epic is referenced with non-closing
language.

**Slice 1 — relay-room (Far):**
- **S1a** Round-robin endpoint-reconnect helper in `:kuilt-session` — wraps a list
  of server endpoints, connects to one, fails over on tear presenting the
  `ResumeToken`; selector injectable (O3). Unit-tested with fake fabrics.
- **S1b** Relay-room assembly in `examples/` — `KtorRoomHost` server + voter
  `RaftNode`s over the room Seam; a learner-client joins via add-learner membership
  (D3) and proposes through forwarding.
- **S1c** Integration test (the slice-1 done-when): N clients, M servers, leader
  change mid-flight, client entry-server killed mid-propose → reconnects → **no
  double-apply** (asserts `ClientSessionTable` skip).

**Slice 2 — point-to-point star (bigger, own sub-spec):**
- **S2a** Sub-spec: per-server raft-message relay (multi-hop fabric) — how a server
  relays a client's 2-peer-Seam raft messages into the cluster Seam.
- **S2b** Implementation + star integration test (mirror of S1c over the star).

**Facade (after the shape is proven):**
- **S3** Extract `:kuilt-cluster` — `ServerCluster` (small voter set + learner
  admission) / `ClusterClient` (round-robin connect + propose + observe) facades,
  lifting the proven examples glue into a published module. New module applies
  `id("kuilt.kmp-library")`.

## Done when

A client connects to any server, submits actions that commit through the leader,
survives its entry-server dying by reconnecting to another, and **never
double-applies** — demonstrated by S1c (relay) and S2b (star). The `:kuilt-cluster`
facade (S3) packages it for consumers.
