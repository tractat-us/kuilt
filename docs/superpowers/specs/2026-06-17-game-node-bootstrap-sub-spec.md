# Sub-spec — `gameNode` bootstrap convenience for `:kuilt-game` (#480)

_Design sub-spec for [#480](https://github.com/tractat-us/kuilt/issues/480).
Part of epic [#474](https://github.com/tractat-us/kuilt/issues/474)._

## Problem

A "tic-tac-toe + chat" author connecting two players over a kuilt `Seam` must
name five raft internals today:

```kotlin
val node = scope.raftNode(
    ClusterConfig.ofVoters(listOf(NodeId(seam.selfId.value), NodeId(peerSeamId.value))),
    SeamRaftTransport(seam),
    InMemoryRaftStorage(),
    RaftConfig(expectVirtualTime = true),  // wait — is this needed?
)
```

The `TurnSequencer` in `:kuilt-game` wraps `RaftNode` beautifully; the
problem is the plumbing *below* it. `gameNode(seam, peerCount = 2)` should
reduce that to a one-liner.

## What's already solved (do not re-derive)

**ID-alignment is done.** `SeamRaftTransport` already exposes
`selfId = NodeId(seam.selfId.value)` and maps every `PeerId` → `NodeId` by
value. The "NodeId must match PeerId.value" constraint from #480 is internal to
the transport. No caller ceremony required.

**`gameNode` is orthogonal to dedup (#484/#493/#495).** The exactly-once
client-serial dedup work adds `ClientId` / `ClientSessionTable` retry semantics.
It does not discover cluster membership, bootstrap a singleton, or drive
`changeMembership`. Do not conflate them; `gameNode` does not depend on dedup
landing.

## Proven bootstrap pattern (from `:kuilt-cluster`)

`ServerCluster.admitLearner` demonstrates the pattern:

1. Start a **singleton-voter cluster** (`ClusterConfig.ofVoters(setOf(self))`)
   so the node can elect itself leader immediately. No other node IDs required
   at construction time.
2. **Observe `seam.peers`** — wait for `peerCount - 1` peers to connect.
3. For each arriving peer: call `RaftNode.changeMembership` to add it as a
   **learner** first (single joint-consensus step, no quorum disruption).
4. Promote each learner to **voter** via a second `changeMembership` call once
   replication is caught up.

`gameNode` wraps exactly this sequence.  The non-singleton peers join via the
symmetric path: they also start as singleton voters, observe `seam.peers`, and
admit *their* perspective of the arriving peers the same way — Raft's joint
consensus ensures only one topology wins.

## Design decisions

### D1 — Host/join asymmetry: **symmetric, one call**

The `Loom`/`Seam` layer is fully symmetric (`weave(Rendezvous.New)` vs.
`weave(Rendezvous.Existing)` is a fabric concern, invisible to `gameNode`).
`raftNode.propose()` already forwards from any non-leader to the leader
(#483), so every seat can drive `TurnSequencer` without knowing who is leader.

Each peer bootstraps a singleton, then expands membership as peers arrive.
Joint consensus serializes concurrent `changeMembership` calls; `gameNode`
retries on `MembershipChangeInProgressException` (same retry pattern as
`ServerCluster.changeMembershipWithRetry`).

**Decision: one symmetric `gameNode(seam, peerCount)` call.**  No host/join
variants; no `firstPeer` flag.

### D2 — When is membership "complete"? **caller supplies `peerCount`**

Two options:
- **Fixed `peerCount`**: caller declares how many seats the game has (e.g. 2
  for tic-tac-toe). `gameNode` suspends until all seats are filled, then
  returns the ready `RaftNode` (and optionally a `TurnSequencer`). Simple,
  deterministic, matches closed-lobby games.
- **Dynamic discovery**: `gameNode` returns immediately; membership expands
  as `seam.peers` grows. Suits open lobbies but makes "game can start" an
  application-level signal the caller must handle anyway.

**Decision: `peerCount` required parameter.** The common case is a fixed-seat
game. Open-lobby support is a follow-on (pass `peerCount = -1` or a separate
overload); deferred. `gameNode` suspends until all peers have joined and their
membership changes have committed before returning — the returned `RaftNode` is
ready to propose.

### D3 — Learner-then-promote vs. all-voters-at-once: **learner-then-promote**

For a turn-based game every seat should be a full voter (proposals survive any
single-node loss in a 3+ player game). `gameNode` promotes learners to voters
automatically after the `changeMembership(learners)` commits.

Joint consensus cost: each admission is two committed config entries. For a
2-player game that's 2 entries total (one admit-as-learner + one promote).
Acceptable. `gameNode` hides the two-phase ceremony.

**Decision: `gameNode` drives learner admission then promotion automatically.**
The returned node is in the final all-voters config.

### D4 — `expectVirtualTime` leakage: **separate test entry point**

`expectVirtualTime` is a `RaftConfig` flag that suppresses the
`TestDispatcher` guard. It has **no effect in production**; it must never
appear in example/production code (repo policy: "optional ≠ tuning" — a
test-only flag in a production API is a latent misuse trap).

**Decision:** `:kuilt-game` ships two entry points:

- `CoroutineScope.gameNode(seam, peerCount, …)` — production. Constructs
  `RaftConfig()` with all defaults; `expectVirtualTime` is never set.
- `CoroutineScope.gameNode(seam, peerCount, raftConfig = …, …)` — or, where
  tests need a real `RaftNode` under virtual time, a `@TestOnly`-annotated
  overload (or a builder in `:kuilt-raft-test`) that accepts an explicit
  `RaftConfig`. Production callers cannot accidentally pass
  `expectVirtualTime = true` because the default overload does not expose it.

Most `:kuilt-game` tests should use `FakeRaftNode` (no `RaftConfig` at all);
only harness-level integration tests that stand up a real node need the escape
hatch.

### D5 — `incoming` single-collection contract (ADR-034)

`SeamRaftTransport` consumes `seam.incoming` via its `incoming: Flow<RaftEnvelope>`.
The `Seam` contract permits **one collector** of `incoming`. Once `gameNode`
wraps the `Seam` in a `SeamRaftTransport` and passes it to `raftNode`, the
caller **must not** also collect `seam.incoming` — doing so races for frames
with the Raft engine and will drop Raft messages silently.

A game that also needs a **chat channel** (the "tic-tac-toe + chat" use case
from #480) must multiplex over a single `Seam` via `MuxSeam`:

```
val mux = MuxSeam(seam)
val raftSeam = mux.channel("raft")   // Seam for Raft traffic
val chatSeam = mux.channel("chat")   // Seam for chat messages
val node = scope.gameNode(raftSeam, peerCount = 2)
```

`gameNode`'s KDoc must warn: *"Do not collect `seam.incoming` after passing
the seam to `gameNode`. For additional channels, pass a `MuxSeam` channel
rather than the raw seam."*

## API shape (indicative — not normative)

```kotlin
// In :kuilt-game, CoroutineScope extension:
suspend fun CoroutineScope.gameNode(
    seam: Seam,
    peerCount: Int,
    storage: RaftStorage = InMemoryRaftStorage(),
): RaftNode
```

`storage` is exposed (defaulting to in-memory) so a caller that needs crash
recovery can inject a persistent implementation. All other raft internals are
hidden. The return type is `RaftNode` rather than `TurnSequencer<A>` because the
action type `A` is caller-defined — the caller wraps the returned node in
`TurnSequencer(node, MyAction.serializer())`.

The exact signature (including whether `storage` is a parameter) is an
implementation-time decision; this spec constrains the *behaviour*, not the
bikeshed.

## Out of scope

- **Exact API signature / parameter names.** This spec fixes behaviour; the
  implementer bikesheds the surface.
- **Open-lobby / dynamic `peerCount`.** Deferred; fixed-count covers all
  closed-seat games.
- **`MuxSeam` ergonomics (#480 "also affected").** A separate issue; `gameNode`
  documents the constraint and points to `MuxSeam`.
- **Persistent storage defaults.** `InMemoryRaftStorage()` is the right default
  for in-process games; SQLite/IndexedDB-backed storage is a consumer concern.
- **Snapshot / log compaction.** `TurnSequencer` already notes this is deferred;
  `gameNode` inherits the assumption.

## Testing (acceptance criteria for the impl issue)

- **Happy path (2 players):** two `gameNode` calls on `InMemoryLoom` seats converge
  to an all-voters cluster; `TurnSequencer.propose` from either seat commits and
  appears on both `committed` flows. Use `MultiNodeRaftSim` / `raftRunTest`; tight
  5 s timeout.
- **Happy path (3 players):** same with three seats and quorum = 2.
- **`incoming` guard:** asserting that a second collector of `seam.incoming` after
  `gameNode` drops Raft messages (documents the constraint in a test).
- **No `expectVirtualTime` in production path:** the production `gameNode` overload
  constructs a `RaftConfig()` with `expectVirtualTime = false`; assert via the
  `strictTestGuard` path that passing the production overload under a
  `TestDispatcher` emits the standard warning (it is *not* suppressed).
