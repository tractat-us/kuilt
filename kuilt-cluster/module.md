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
transport) or via `CoroutineScope.clusterClient()` (production relay-room path).

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

### `ManagedSeam` (commonMain)

A `Seam` whose *backing* `Seam` can be hot-swapped on transport tear without
recreating the `RaftNode`. The client builds its `RoutedRaftTransport` and
`RaftNode` once over a `ManagedSeam`; on reconnect only the backing seam is
`swap`ped, so the node keeps its identity, log, and its single collector of
`incoming` across failovers. This is the primitive that makes
`CoroutineScope.clusterClient()`'s cross-server reconnect possible.

Thread-safe: the current backing-seam pointer is guarded by an atomicfu reentrant
lock (never held across a suspend); `incoming` is a hot `MutableSharedFlow` stable
across swaps, and each `swap` cancels the previous per-swap relay coroutine before
starting the next, so at most one collector of any backing seam is ever live
(single-collection preserved). Correct under a multi-threaded dispatcher.

### `ServerCluster` (jvmAndAndroidMain)

Server-side cluster facade: an M-voter `VoterMesh` plus a relay accept loop
that admits learner clients via `KtorRoomHost`. Each admitted WebSocket
connection is a two-peer Room; the server derives the learner's `NodeId` from
the room roster, registers the connection's spoke with the `RaftRelayHub`, and
issues a `changeMembership` to add the learner. The hub routes each learner-inbound
frame **by its `RaftRelay.dest`** to exactly that voter's inbound and preserves the
true sender as `RaftRelay.origin` — no leader-sniffing, no sender-restamping in
either direction.

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
to fresh-join: each server's reconnect-window registry is in-memory and
per-host-room, so server-B has no window state for a token issued by server-A.
`ClusterClient` treats `ResumeResult.WindowClosed` as a fall-back-to-fresh-join
signal — reconnect is correct, it costs a re-snapshot on the learner's log.

## Exactly-once proposals

`propose(command)` delegates to `RaftNode.propose`, which auto-mints a
monotonic `requestId`. `propose(command, requestId)` is the public cross-crash
exactly-once overload. The server's `ClientSessionTable.shouldApply` filters
duplicates.

## NodeId ↔ PeerId alignment constraint

Each voter's `NodeId` must equal `NodeId(serverPeerId.value)` — the server's
`KtorRoomHost.serverPeerId` cast to a `NodeId`. The `RaftRelayHub` carries the
true voter as the `RaftRelay.origin` (never re-stamping it with the relaying
server's fabric sender), and the client's player relay transport
(`playerRelayTransport`) maps that `origin` back to a `NodeId` for Raft routing
and credit. Mismatched IDs cause silently dropped AppendEntries.

See `docs/architecture.md#server-cluster-topology` and `docs/usage.md#server-cluster-topology-kuilt-cluster-jvmandroid`.
