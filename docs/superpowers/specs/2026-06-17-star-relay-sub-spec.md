# Sub-spec — per-server raft-message relay (point-to-point star, S2a)

_Part of [epic #485](https://github.com/tractat-us/kuilt/issues/485), Slice 2.
Resolves the design sub-issue [#511](https://github.com/tractat-us/kuilt/issues/511).
Parent spec: [`2026-06-16-server-cluster-topology-design.md`](2026-06-16-server-cluster-topology-design.md) (D1, shape 2).
Plan §S2a: [`../plans/2026-06-16-server-cluster-topology.md`](../plans/2026-06-16-server-cluster-topology.md)._

## Status: documents a design that already shipped

The point-to-point star relay was **implemented as part of the `:kuilt-cluster`
facade work** (the failover line — #541/#544/#545/#557), ahead of this sub-spec
being written. This document therefore records the design **as it landed** and
resolves #511's four open axes — **framing, addressing, back-pressure, and
`ResumeToken` across the star** — by reference to the shipped types and tests,
rather than proposing a design to build.

The implementing slice **S2b** (#512 — star impl + integration test) is likewise
satisfied by the same code; see [§S2b status](#s2b-status).

## What "star" means here (vs. relay-room)

The parent spec's D1 names two periphery shapes. They are **both present in the
repo but in different places**, and the distinction is load-bearing:

- **Relay-room (slice 1 demo, `examples/`).** A client connecting to a
  `KtorRoomHost` endpoint is *logically a peer on the shared cluster `Seam`* —
  voters and clients share one multi-peer Seam, and propose-forwarding routes the
  client to the leader. No per-server relay.
- **Point-to-point star (slice 2, the `:kuilt-cluster` facade — this doc).** The
  voter core meshes **separately** (in-process `Channel` transports under
  simulation; real WebSocket sockets in the M=3 E2E). Each client holds a strict
  **2-peer `Seam`** to exactly one server. The client's Seam *cannot address the
  leader*, so the attached server **relays** the client's raft messages into the
  core. This is the multi-hop relay D1 called "materially bigger."

`:kuilt-cluster` is the **star**: `buildVoterChannelMesh` wires a voter-only mesh;
`ServerCluster.admitLearner` accepts each client as a 2-peer `Room`; `LearnerRouter`
is the relay between the two. (architecture.md previously labelled this section
"Relay-room implementation" — corrected alongside this spec.)

## Design axes resolved

### A1 — Framing

Raft messages ride the client room's dedicated **`"raft"` channel**
(`room.channel("raft")` server-side; the client's `SeamRaftTransport` collects the
matching channel). The frame is the opaque `Swatch`: its `payload` is the raft
wire bytes, its `sender: PeerId` carries the originator.

The relay translates between the `Seam`'s `PeerId` space and Raft's `NodeId` space
by **value identity** — `RaftEnvelope(NodeId(swatch.sender.value), swatch.payload)`
inbound, and a `NodeId.value`-keyed `PeerId` outbound. No separate framing header
is introduced; the channel mux (`:kuilt-core`'s `MuxSeam`) already isolates raft
traffic from any other room channel.

_Implementing types:_ `ManagedRaftTransport.startRelay` (client→relay framing),
`LearnerRouter.addLearner` (relay→voter framing).

### A2 — Addressing

Three hops, each with a fixed rule:

1. **Client → relay.** The client's `ManagedRaftTransport.sendTo` ignores the
   Raft-supplied target `NodeId` and always sends to its **single relay peer**
   (`peers.single { it != selfId }`). The 2-peer Seam has exactly one other peer.
   The Raft engine forwards a proposal to the *real* leader's `NodeId` (read from
   the AppendEntries body), which is generally **not** the relay's `PeerId`;
   addressing it directly would hit an absent peer and silently drop. Sending to
   the relay and letting it route on is what lets a client keep committing through
   **any** relay endpoint regardless of which voter leads — the precondition for
   failover without moving leadership (#544).
2. **Relay → leader (inbound).** `LearnerRouter` routes each inbound learner
   envelope to the **current leader voter's inbound flow only** — never fan-all.
   A learner only ever legitimately addresses the leader (Forward /
   AppendEntriesResponse / InstallSnapshotResponse). Fanning to all voters makes
   followers reply `NotLeader` inline, racing and beating the leader's committed
   reply — a deterministic M>1 bug (#545). If no leader is currently known the
   envelope is dropped; the client's propose retries after `LeadershipLost`.
3. **Voter → client (outbound).** `RaftTransport.sendTo(learnerId, …)` for a
   learner peer dispatches to `LearnerRouter.sendToLearner`, which calls
   `seam.broadcast` on that learner's 2-peer Seam (stamped with the server's
   `serverPeerId`).

**Alignment constraint.** Each voter's `NodeId` must equal
`NodeId(serverPeerId.value)`. The relay stamps `broadcast` as `serverPeerId`; the
client maps that sender back to a `NodeId`. Mismatched IDs cause silently dropped
AppendEntries. (architecture.md §"`NodeId` ↔ `PeerId` alignment".)

### A3 — Back-pressure

**Current posture: unbounded buffering, by design-debt not by decision.**

- Voter inbound is `MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)` fed by
  non-suspending `tryEmit`; the in-process voter channels are `Channel.UNLIMITED`.
- The client's `ManagedRaftTransport.incoming` is likewise an unbounded
  `MutableSharedFlow`.

This trades memory for never blocking the relay/collect path — adequate for the
current "small voter set, modest learner count" target and for virtual-time tests
(where the scheduler bounds work). It is **not** a back-pressure *design*: a
pathological slow consumer or a flood from a misbehaving client grows the buffer
without bound.

**Deferred (follow-up, non-blocking for S2b):** a bounded buffer with an explicit
overflow policy (drop-oldest for idempotent retried frames, or suspend-the-sender
for forwarded proposals) once a learner-fan-out load target exists. Tracked as a
post-slice follow-up alongside learner-GC (epic O1 / #495). Flagged here so the
unbounded buffers read as a known limitation, not an oversight.

### A4 — `ResumeToken` across the star

A `ResumeToken` is keyed on `RoomId` and is **leader-identity-free**, so it
survives a *leader* change within one server. It does **not** survive an
*entry-server* change: each server's `JoinerReconnectController` window registry
is in-memory and per-host-room, so a token issued by server-A is unknown to
server-B (proven by #532).

**Resolution: cross-server failover degrades to fresh-join.** On rotation to a new
endpoint the client presents its token, receives `ResumeResult.WindowClosed`, and
`ClusterClient` treats that as a fall-back-to-fresh-join signal. This is **correct**
— the learner keeps its stable `NodeId`, re-admits via add-learner membership, and
resumes proposing against the same Raft log; the only cost is a re-snapshot of the
learner's log (the leader re-sends from its baseline). Exactly-once is preserved
across the gap by the **pinned `requestId`** retried until it lands (the server's
`ClientSessionTable.shouldApply` dedups), independent of resume vs. fresh-join.

A shared cross-server reconnect-window registry (so failover could *resume* rather
than re-snapshot) is an optional latency optimisation, explicitly out of scope.

## S2b status

S2b (#512 — star implementation + integration test, "mirror of S1c over the star")
is satisfied by shipped code:

- **Implementation:** `ServerCluster` + `LearnerRouter` + `ManagedRaftTransport` +
  `VoterMesh` in `:kuilt-cluster`.
- **Star done-when tests (real socket):**
  - `ServerClusterE2ETest` — M=1: `ClusterClient.propose` commits end-to-end
    through the facade.
  - `ServerClusterM3E2ETest` — M=3: propose commits and replicates to all three
    voters (the production proof of the leader-routing fix #545).
  - `ClusterClientFailoverE2ETest` — M=3, two relays on a shared mesh: client
    commits → entry-relay killed → reconnects to surviving relay keeping its
    pinned `selfPeerId` → retries the **same `requestId`** → commits on the same
    log with **no double-apply**.

**Residual gap vs. S1c's exact wording:** S1c says "N clients" (plural) and "leader
change mid-flight." The star E2E proves the *mechanism* with one client and a
stable-leader failover. A multi-client + forced-mid-flight-leader-change star
done-when is a worthwhile hardening test but not a design question — captured as a
follow-up on #512 rather than a blocker.

## Outcome

- #511 (`needs-design`) is resolved by this document — all four axes have a stated,
  shipped resolution.
- #512's implementation and core integration test already live on `main`; only an
  optional multi-client/leader-change hardening test remains.
