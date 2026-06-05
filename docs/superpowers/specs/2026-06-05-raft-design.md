# kuilt-raft design

**Date:** 2026-06-05  
**Status:** approved  
**Module:** `:kuilt-raft` (new)

## Problem

Fireworks needs distributed consensus for two things: electing a dealer/host among players, and replicating game moves so every peer applies them in the same order. There is no mature Raft library for Kotlin Multiplatform. This spec describes a kuilt-native implementation structured to be extractable into a standalone library later.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Approach | Single `kuilt-raft` module, `RaftTransport` interface | Extractable at the interface boundary; no premature Gradle split |
| Membership | Static cluster config at construction | Dynamic membership (joint consensus) is dramatically complex; `ClusterConfig` is a typed value so dynamic can be added without breaking the API |
| Storage | `RaftStorage` interface, inject at construction | Platform storage semantics differ (browser vs SQLite vs in-memory); interface in from day one, default `InMemoryRaftStorage` for players |
| Server role | Non-voting learner | Server persists the log for durability/reconnect but never votes; leader is always a player |
| Transport abstraction | `RaftTransport` interface, `SeamRaftTransport` adapter | `RaftNode` has no kuilt import; other transports can implement `RaftTransport` directly |

## Module structure

```
kuilt-core  ◄──  kuilt-raft
                  ├── RaftNode            (pure Raft logic — no kuilt import)
                  ├── RaftTransport       (interface)
                  ├── RaftStorage         (interface)
                  ├── ClusterConfig       (voters + learners)
                  ├── LogEntry
                  ├── RaftRole
                  ├── RaftConfig          (timing knobs)
                  ├── InMemoryRaftStorage (default impl)
                  └── SeamRaftTransport   (kuilt adapter — only file touching kuilt-core)
```

**Extraction path:** when a standalone `raft-kt` library is wanted, move everything except `SeamRaftTransport` to a new repo. No refactoring required — the interface boundary already exists.

## Types

### `NodeId`

```kotlin
@JvmInline value class NodeId(val value: String)
```

Distinct from `PeerId` — Raft is transport-agnostic. `SeamRaftTransport` converts trivially via the string value.

### `ClusterConfig`

```kotlin
data class ClusterConfig(
    val voters: Set<NodeId>,
    val learners: Set<NodeId> = emptySet(),
)
```

Static for now. Future dynamic membership extends this type rather than changing the `RaftNode` constructor.

### `LogEntry`

```kotlin
data class LogEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
)
```

`command` is opaque — kuilt-raft does not interpret it. The consumer (Fireworks) encodes game moves into `command` bytes.

### `RaftRole`

```kotlin
sealed interface RaftRole {
    data object Leader    : RaftRole   // = dealer/host in Fireworks
    data object Follower  : RaftRole
    data object Candidate : RaftRole
    data object Learner   : RaftRole   // server — receives log, never votes
}
```

### `RaftTransport`

```kotlin
interface RaftTransport {
    val selfId: NodeId
    val peers: StateFlow<Set<NodeId>>
    suspend fun sendTo(peer: NodeId, message: ByteArray)
    val incoming: Flow<RaftEnvelope>
}

data class RaftEnvelope(val from: NodeId, val bytes: ByteArray)
```

Maps directly onto `Seam`. Thin enough that any point-to-point messaging layer can implement it without pulling in kuilt.

### `RaftStorage`

```kotlin
interface RaftStorage {
    suspend fun term(): Long
    suspend fun saveTerm(term: Long)
    suspend fun votedFor(): NodeId?
    suspend fun saveVotedFor(nodeId: NodeId?)
    suspend fun appendEntries(entries: List<LogEntry>)
    suspend fun entries(fromIndex: Long = 0L): List<LogEntry>
    suspend fun truncateFrom(index: Long)
}
```

`InMemoryRaftStorage` is the default, suitable for players. The server injects a persistent implementation (e.g. SQLite on JVM/Android, IndexedDB wrapper on wasmJs).

### `RaftConfig`

```kotlin
data class RaftConfig(
    val electionTimeoutMin: Duration = 150.milliseconds,
    val electionTimeoutMax: Duration = 300.milliseconds,
    val heartbeatInterval: Duration = 50.milliseconds,
)
```

Randomised election timeout within `[min, max]` is standard Raft. Tests inject fast timings via `UnconfinedTestDispatcher`.

### `RaftNode`

```kotlin
class RaftNode(
    val clusterConfig: ClusterConfig,
    val transport: RaftTransport,
    val storage: RaftStorage,
    val scope: CoroutineScope,
    val raftConfig: RaftConfig = RaftConfig(),
)
```

**Observed state:**

```kotlin
val role: StateFlow<RaftRole>
val leader: StateFlow<NodeId?>
val commitIndex: StateFlow<Long>
val committed: Flow<LogEntry>   // hot; backfill arrives in order on reconnect
```

**Commands:**

```kotlin
// Propose a command to the cluster. Returns the log index assigned.
// Throws NotLeaderException if this node is not currently the leader
// (including learners, which can never be leader).
suspend fun propose(command: ByteArray): Long

suspend fun close()
```

Non-leader nodes use `leader.value` to discover who to forward a proposal to (via their own application-layer message). kuilt-raft does not implement forwarding — that is the consumer's responsibility.

`committed` is a hot `SharedFlow`. A follower or learner that was partitioned and rejoins will see backfill entries arrive in log order — the consumer applies them to rebuild state correctly.

### `SeamRaftTransport`

```kotlin
class SeamRaftTransport(seam: Seam) : RaftTransport {
    override val selfId = NodeId(seam.selfId.value)
    override val peers = seam.peers.mapState { set -> set.map { NodeId(it.value) }.toSet() }
    override suspend fun sendTo(peer: NodeId, message: ByteArray) =
        seam.sendTo(PeerId(peer.value), message)
    override val incoming = seam.incoming
        .filter { it.sender != null }
        .map { RaftEnvelope(NodeId(it.sender!!.value), it.payload) }
}
```

This is the only file in `kuilt-raft` that imports `us.tractat.kuilt.core.*`.

## Fireworks integration

```kotlin
// Both players and server call this at game start.
// Server passes SqliteRaftStorage(); players pass InMemoryRaftStorage().
val node = RaftNode(
    clusterConfig = ClusterConfig(
        voters  = playerIds.map { NodeId(it.value) }.toSet(),
        learners = setOf(NodeId(serverId.value)),
    ),
    transport = SeamRaftTransport(seam),
    storage   = InMemoryRaftStorage(),
    scope     = gameScope,
)

// The elected leader is the dealer.
node.role.collect { role ->
    if (role is Leader) becomeDealer()
}

// Propose a move (only the leader does this, or redirect to leader).
node.propose(Json.encodeToByteArray(move))

// All nodes apply committed moves in order.
node.committed.collect { entry ->
    val move = Json.decodeFromByteArray<GameMove>(entry.command)
    gameState.apply(move)
}
```

## Internal Raft mechanics

Standard Raft (Ongaro & Ousterhout §3–5):

- **Leader election** — randomised election timeout; `RequestVote` RPC. Learners skip election entirely (no timeout, no vote).
- **Log replication** — leader sends `AppendEntries` to all voters and learners; commits when a majority of voters acknowledges.
- **Heartbeats** — empty `AppendEntries` at `heartbeatInterval` to suppress elections.
- **Term / voted-for persistence** — written to `RaftStorage` before any RPC response, per the paper's safety rules.

Internal RPCs are serialised with `kotlinx.serialization` into the `ByteArray` frames carried by `RaftTransport`. The wire format is internal to `kuilt-raft`; consumers never touch it.

## Testing strategy

- **`RaftConformanceSuite`** — abstract test class, one `newNodeTriple()` abstract method returning three wired-up `RaftNode`s backed by `InMemoryRaftStorage` and a fake in-process transport. Covers: leader election, log replication, follower catch-up, leader failure + re-election, learner log delivery.
- **`InMemoryRaftNetwork`** — in-process fake transport for tests (analogous to `InMemoryLoom`). Supports injecting artificial delays and message drops for partition testing.
- **Coroutine determinism** — inject `UnconfinedTestDispatcher` via the `scope` parameter; fast election timings via `RaftConfig`. Pattern mirrors `docs/testing-coroutine-determinism.md`.

## Explicitly out of scope

- **Dynamic membership / joint consensus** (§6) — `ClusterConfig` type is ready for it; implementation deferred.
- **Log compaction / snapshotting** (§7) — for game-length sessions, log size is bounded. Add later if needed.
- **Pre-vote extension** — reduces disruptive elections from partitioned nodes. Worthwhile eventually; not needed for correctness.
- **Read-only query optimisation** (§8) — all reads go through the log for now.
- **Multi-Raft / sharding** — one consensus group per game session; no sharding needed.
