# Module kuilt-cluster

**kuilt-cluster** is the server-cluster facade module, exposing `ClusterClient` for learner-client access to a kuilt-raft relay-room cluster.

## What it provides

- **`ClusterClient`** — wraps a Raft learner node and exposes `propose`, `committed`, `role`, and `close()`. Provides exactly-once semantics via the `requestId` overload and `ClientSessionTable` dedup on the apply side.
- **`ClusterEndpoints`** — value class holding the ordered endpoint list and rotation policy (default: round-robin via `ServerClusterReconnect`).

## Dependency direction

```
:kuilt-cluster
  api(:kuilt-core)     ← Loom, Seam, Tag, InMemoryLoom
  api(:kuilt-raft)     ← RaftNode, ClusterConfig, ClientSessionTable, Committed
  api(:kuilt-session)  ← ServerClusterReconnect, SeamRoomFactory, ResumeToken
```

No arrow points back into `:kuilt-core`. `:kuilt-session` does NOT depend on `:kuilt-raft` — no cycle.

## S3a / S3b / S3c scope

**S3a (this module):**
- Module scaffold, `ClusterClient` API, `ClusterEndpoints`, `module.md`, `@sample`.
- `clusterClientWithNode()` — test/caller-managed construction path.
- `CoroutineScope.clusterClient()` declared but throws `UnsupportedOperationException` — production relay-room wiring is S3b.
- Tier-(a) behavioural tests against `FakeRaftNode`: propose, dedup, committed stream, role, endpoint rotation, WindowClosed→fresh-join.

**S3b (future):**
- `ManagedRaftTransport` — a `RaftTransport` whose backing `Seam` can be replaced on transport tear without recreating the `RaftNode`.
- Wire `CoroutineScope.clusterClient()` to use `ServerClusterReconnect` + `SeamRaftTransport` + reconnect loop.

**S3c (future):**
- `ServerCluster` — JVM/Android-only voter set + learner admission facade.

## Failover model

On transport tear `ServerClusterReconnect` advances to the next endpoint and re-presents the `ResumeToken`. Cross-server resume always returns `ResumeResult.WindowClosed` (proven by #532): each server's `JoinerReconnectController` is in-memory and per-host-room, so server-B has no window state for a token issued by server-A. `ClusterClient` treats `WindowClosed` as a fall-back-to-fresh-join signal.

## Exactly-once proposals

`propose(command)` delegates to `RaftNode.propose`, which auto-mints a monotonic `requestId`. `propose(command, requestId)` is the public cross-crash dedup overload. The server's `ClientSessionTable.shouldApply` filters duplicates.

@sample us.tractat.kuilt.cluster.samples.ClusterClientSample.connectAndPropose
