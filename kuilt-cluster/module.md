# Module kuilt-cluster

**kuilt-cluster** is the server-cluster facade for `kuilt-raft`. It packages
the two-tier overlay topology — a complete-graph voter core (`K_m`) plus a
sparse learner periphery — as two high-level types: `ServerCluster` (server side)
and `ClusterClient` (client side).

## What it provides

### `ClusterClient` (commonMain)

Wraps a Raft learner `RaftNode` and exposes:

- **`propose(command)`** — proposes with an auto-minted monotonic `requestId`.
  Forwards to the current leader; suspends until committed. Returns the
  committed `LogEntry`.
- **`propose(command, requestId)`** — cross-crash exactly-once overload.
  Persist the `requestId` before calling; replay it after a crash or failover.
  The server's `ClientSessionTable.shouldApply` deduplicates retries.
- **`committed: Flow<Committed>`** — the committed log stream. Single-collection
  contract (mirror of `RaftNode.committed`): collect once per `ClusterClient`;
  fan out with `shareIn` if multiple consumers are needed.
- **`role: StateFlow<RaftRole>`** — always `RaftRole.Learner` in the relay
  model.
- **`close()`** — cancels the underlying `RaftNode`.

Obtain an instance via `clusterClientWithNode(raftNode)` (tests / caller-managed
transport) or via `CoroutineScope.clusterClient()` (relay-room — see [Current
scope](#current-scope)).

@sample us.tractat.kuilt.cluster.samples.ClusterClientSample.connectAndPropose

### `ClusterEndpoints` (commonMain)

Value class holding the ordered endpoint `List<Tag>` and rotation policy
(default: deterministic round-robin via `ServerClusterReconnect`).

```kotlin
val endpoints = ClusterEndpoints(
    endpoints = listOf(serverTag1, serverTag2, serverTag3),
)
```

### `VoterMesh` (commonMain)

An M-voter Raft mesh — a complete-graph (`K_M`) cluster of voter `RaftNode`s.
Exposes `voterNodes: Map<NodeId, RaftNode>`, `committed: Flow<Committed>` from
the first voter (for single-consumer scenarios), and `awaitLeader()`. Node
lifetimes are tied to the injected `CoroutineScope`.

Used directly in tests (wired via `MultiNodeRaftSim` from `:kuilt-raft-test`)
and as the voter layer inside `ServerCluster`.

### `ManagedRaftTransport` (commonMain)

A `RaftTransport` whose backing `Seam` can be replaced on transport tear without
recreating the `RaftNode`. The `RaftNode` keeps its identity and in-flight state
across reconnects; only the underlying `Seam` is swapped. This is the primitive
that makes `CoroutineScope.clusterClient()` possible.

Thread-safe: the current `SeamRaftTransport` pointer is guarded by an atomicfu
reentrant lock; `incoming` is a hot `MutableSharedFlow` relayed from each seam;
the lock is never held across a suspend call.

### `ServerCluster` (jvmAndAndroidMain)

Server-side cluster facade: an M-voter `VoterMesh` plus a relay accept loop
that admits learner clients via `KtorRoomHost`. Each admitted WebSocket
connection is a two-peer Room; the server derives the learner's `NodeId` from
the room roster, routes Raft messages through `LearnerRouter`, and issues a
`changeMembership` to add the learner.

```kotlin
val cluster = serverScope.serverCluster(
    host = KtorRoomHost(application, path = "/ws/cluster",
                        serverPeerId = PeerId("server-1"),
                        pattern = Pattern("cluster-room")),
    voterIds = listOf(NodeId("server-1")),
    raftConfig = RaftConfig(/* … */),
)

serverScope.launch { cluster.start() }  // relay accept loop

val leader = cluster.awaitLeader()
cluster.committed.collect { /* apply entries */ }
cluster.close()
```

## Dependency direction

```
:kuilt-cluster
  api(:kuilt-core)       ← Loom, Seam, PeerId, Tag
  api(:kuilt-raft)       ← RaftNode, ClusterConfig, ClientSessionTable, Committed
  api(:kuilt-session)    ← ServerClusterReconnect, SeamRoomFactory, ResumeToken
  impl(:kuilt-websocket) ← KtorRoomHost (jvmAndAndroidMain only)
```

No arrow points back into `:kuilt-core`. `:kuilt-session` does NOT depend on
`:kuilt-raft` — no cycle.

## Failover model

On transport tear `ServerClusterReconnect` advances to the next endpoint from
the `ClusterEndpoints` list and reconnects. Cross-server resume always degrades
to fresh-join (proven by #532): each server's `JoinerReconnectController` is
in-memory and per-host-room, so server-B has no window state for a token issued
by server-A. `ClusterClient` treats `ResumeResult.WindowClosed` as a
fall-back-to-fresh-join signal — reconnect is correct, it costs a re-snapshot on
the learner's log.

## Exactly-once proposals

`propose(command)` delegates to `RaftNode.propose`, which auto-mints a
monotonic `requestId`. `propose(command, requestId)` is the public cross-crash
exactly-once overload. The server's `ClientSessionTable.shouldApply` filters
duplicates.

## NodeId ↔ PeerId alignment constraint

Each voter's `NodeId` must equal `NodeId(serverPeerId.value)` — the server's
`KtorRoomHost.serverPeerId` cast to a `NodeId`. The `LearnerRouter` stamps
`Seam.broadcast`'s sender as `serverPeerId`; the client's `SeamRaftTransport`
maps that sender to a `NodeId` for Raft message routing. Mismatched IDs cause
silently dropped AppendEntries.

## Current scope

- **M=3 voter mesh** is proven under simulation (#541). **M=1** is proven over
  real Ktor WebSocket sockets (`ServerClusterE2ETest`, S3b-3 of #513). Real-socket
  M>1 E2E is a follow-up (see #545).
- **`CoroutineScope.clusterClient()`** is declared and wires the full reconnect
  loop but requires a stable client identity on the loom (see #544). Use
  `clusterClientWithNode()` with a caller-managed `RaftNode` + `SeamRaftTransport`
  for the production relay-room path in the meantime.
- **Cross-server resume** degrades to fresh-join (see #532); see the Failover
  model section above.

See `docs/architecture.md#server-cluster-topology` and `docs/usage.md#server-cluster-topology-kuilt-cluster-jvmandroid`.
