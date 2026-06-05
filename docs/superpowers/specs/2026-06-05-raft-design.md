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

`RaftNode` is an interface (following kuilt's `Seam`/`Loom`/`Room` pattern — testable, fakeable).

**Observed state:**

```kotlin
interface RaftNode {
    val role: StateFlow<RaftRole>
    val leader: StateFlow<NodeId?>
    val commitIndex: StateFlow<Long>

    // Hot SharedFlow of committed entries, in log order.
    // Followers and learners collect this to drive state.
    // A partitioned node that rejoins sees backfill entries arrive in order.
    val committed: Flow<LogEntry>

    // Propose a command. Suspends until a quorum has committed the entry.
    // Returns the committed LogEntry (index + term + command).
    // Throws NotLeaderException if this node is not currently the leader.
    // Throws LeadershipLostException if leadership is lost while waiting.
    // Learners always throw NotLeaderException (they can never be leader).
    suspend fun propose(command: ByteArray): LogEntry

    suspend fun close()
}
```

Non-leader nodes use `leader.value` to discover who to forward a proposal to (via their own application-layer message). kuilt-raft does not implement forwarding — that is the consumer's responsibility.

**Forwarding and idempotency.** `LeadershipLostException` means the original proposal may or may not have committed before the leader changed — a blind retry risks double-applying. Consumers must include an idempotency key in the command payload so the state machine can detect and ignore duplicates:

```kotlin
@Serializable
data class GameCommand(
    val proposalId: String = uuid4(),   // stable across retries
    val move: GameMove,
)

// Non-leader forward loop
suspend fun proposeWithRetry(node: RaftNode, command: GameCommand) {
    while (true) {
        val target = node.leader.filterNotNull().first()
        try { sendToLeader(target, command); return }
        catch (_: LeadershipLostException) { /* await new leader, retry */ }
    }
}
```

The state machine deduplicates on `proposalId` before applying. kuilt-raft carries command bytes opaquely — dedup is always the consumer's concern.

**`committed` emission semantics.** Per node instance: exactly once, in index order. On partition+heal: only entries not yet seen by this instance (no re-emissions). On crash+restart: the new node instance re-emits all entries from the storage log (index 0 upward) as they are replicated. Consumers that persist their own applied state should track `LogEntry.index` to avoid re-applying entries they already handled.

**Membership vs reachability.** `ClusterConfig.voters` is cluster membership — the static set of nodes that participate in consensus. `RaftTransport.peers` is reachability — which nodes happen to be connected right now. `RaftNode` reads membership from `ClusterConfig` and reachability from the transport; a voter absent from `transport.peers` is "down", not "removed". They are separate concerns.

**Learner passivity.** The learner (server) receives `AppendEntries` replication from the leader identically to a follower but never originates backfill. It does not accelerate recovery: if a voter partition means no quorum can form, the learner's complete persistent log does not help elect a leader. Cold-start correctness relies entirely on whichever nodes form a quorum having sufficient persistent log coverage via `RaftStorage`.

**Construction — `CoroutineScope` extension:**

```kotlin
fun CoroutineScope.raftNode(
    clusterConfig: ClusterConfig,
    transport: RaftTransport,
    storage: RaftStorage,
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode
```

The node's internal coroutines are launched as children of the calling scope's `Job`. Cancelling the scope stops the node cleanly — no separate `close()` needed for scope-driven teardown. Call `close()` only when shutting down the node while keeping the parent scope alive (e.g. game ends, scope is reused).

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
val node = gameScope.raftNode(
    clusterConfig = ClusterConfig(
        voters   = playerIds.map { NodeId(it.value) }.toSet(),
        learners = setOf(NodeId(serverId.value)),
    ),
    transport = SeamRaftTransport(seam),
    storage   = InMemoryRaftStorage(),
)

// The elected leader is the dealer — observe role changes reactively.
node.role
    .filter { it is Leader }
    .first()
    .let { becomeDealer() }

// Propose a move: suspends until a quorum commits it.
// propose() throws if not leader — forward via leader.value if needed.
try {
    val committed = node.propose(Json.encodeToByteArray(move))
    // committed.index is the durable log position
} catch (e: NotLeaderException) {
    // forward the proposal to node.leader.value via app-layer message
} catch (e: LeadershipLostException) {
    // leadership changed mid-flight; re-check and retry
}

// All nodes (leader, follower, learner) apply committed entries in order.
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

The validation approach follows the community gold standard (MIT 6.5840, etcd, MadRaft): deterministic discrete-event simulation + per-step safety invariant assertion + scenario matrix. This finds the classes of bugs that unit tests miss: log divergence after leadership churn, split vote, log-backup under unreliable networks.

### Simulation harness — `RaftSimulation`

```kotlin
// Central harness. Tests control the network; nodes never touch real I/O.
class RaftSimulation(n: Int, raftConfig: RaftConfig = RaftConfig()) {
    val nodes: List<RaftNode>

    // Partition: drop all messages between the two sets
    fun partition(a: Set<NodeId>, b: Set<NodeId>)
    fun heal()

    // Message bus controls
    fun dropNextMessage(from: NodeId, to: NodeId)
    fun delayMessages(from: NodeId, to: NodeId, delay: Duration)

    // Kill and restart a node (new RaftNode, same storage)
    fun crash(id: NodeId)
    fun restart(id: NodeId)

    // Assert all four safety invariants right now
    fun checkInvariants()
}
```

Every node in `RaftSimulation` uses:
- `UnconfinedTestDispatcher(testScheduler)` — deterministic coroutine scheduling
- Per-node `Channel<RaftEnvelope>` pair as the message bus — the test controls delivery
- `InMemoryRaftStorage` by default; `crash()`/`restart()` re-creates the node with the same storage instance to simulate restart

### Four safety invariants (checked after every delivered message)

These correspond to the Raft paper's safety properties and must never be violated:

1. **Election Safety** — at most one leader per term across all nodes
2. **Log Matching** — if two nodes' logs agree on `(index, term)`, all preceding entries are identical
3. **Leader Completeness** — if an entry is committed in term T, every leader elected in term > T has that entry in its log
4. **State Machine Safety** — no two nodes have applied different commands at the same log index

### Scenario matrix (MIT 6.5840 checklist, adapted)

| Scenario | What it tests |
|----------|---------------|
| `initialElection` | Single leader elected; term advances after restart |
| `reElection` | Leader crash → re-election within timeout |
| `manyElections` | 7 rounds of leader churn, invariants hold throughout |
| `basicReplication` | Entries proposed by leader arrive on all followers in order |
| `followerFailure` | One follower crashes; quorum continues; follower catches up on rejoin |
| `leaderFailure` | Leader crashes mid-proposal; new leader re-proposes; no duplicate commits |
| `failNoAgree` | Quorum lost (majority partitioned); no commit progress; invariants hold |
| `concurrentProposals` | N coroutines propose simultaneously; all commit in some order |
| `rejoinPartitionedLeader` | Isolated ex-leader rejoins; reverts to follower; log reconciles |
| `logBackup` | New leader must back up far over a follower's divergent log (§5.3 fast backup) |
| `persistence` | Node crashes and restarts; rejoins with same term/votedFor/log |
| `unreliableChurn` | Random drops + delays + crashes + heals; state machine stays consistent |
| `learnerDelivery` | Learner receives all committed entries; never votes; never becomes leader |
| `learnerCatchup` | Learner partitioned then healed; backfill arrives in order |

`logBackup` and `unreliableChurn` are the hardest. `logBackup` requires the fast log-backup optimisation (§5.3 of the paper); without it the test takes O(n²) round-trips and times out.

### State machine linearizability — JetBrains Lincheck

Layer a thin `SimulatedKV` state machine on top of `committed`:

```kotlin
class SimulatedKV(val node: RaftNode) {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun compareAndSwap(key: String, expected: String?, new: String?): Boolean
}
```

Drive this with concurrent coroutines and verify the observed history is linearizable with respect to a sequential map. [JetBrains Lincheck](https://github.com/JetBrains/lincheck) covers the concurrent correctness layer; the simulation scenarios cover distributed safety.

### Coroutine determinism

All tests use `runTest` + `UnconfinedTestDispatcher(testScheduler)`. Fast election timings (`RaftConfig(electionTimeoutMin = 5.ms, heartbeatInterval = 1.ms)`) keep the suite fast. Mirrors `docs/testing-coroutine-determinism.md`.

### What's out of scope for validation

- **TLA+ trace validation** — the canonical Raft TLA+ spec exists ([ongardie/raft.tla](https://github.com/ongardie/raft.tla)); wiring a Kotlin implementation to emit TLC-verifiable traces is significant infrastructure work with no established tooling. Not doing it.
- **Jepsen / Elle** — useful for black-box network chaos against a live cluster; overkill for a library-internal conformance suite.

## Potential standalone library names

If `:kuilt-raft` is ever extracted into its own library, candidate names under consideration:

- **rakt** — anagram of Raft; short, memorable, available as a Kotlin identifier
- **Flotta** — Old Norse for a raft-island; thematic fit with the kuilt/weaving/fabric naming family

Neither is decided. Recorded here so the choice is visible when extraction day comes.

## Explicitly out of scope

- **Dynamic membership / joint consensus** (§6) — `ClusterConfig` type is ready for it; implementation deferred.
- **Log compaction / snapshotting** (§7) — for game-length sessions, log size is bounded. Add later if needed.
- **Pre-vote extension** — reduces disruptive elections from partitioned nodes. Worthwhile eventually; not needed for correctness.
- **Read-only query optimisation** (§8) — all reads go through the log for now.
- **Multi-Raft / sharding** — one consensus group per game session; no sharding needed.
