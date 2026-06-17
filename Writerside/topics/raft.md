# Consensus (Raft)

Use `kuilt-raft` when your feature needs strict agreement, not best-effort merge.
It gives every node the same strongly consistent, totally ordered log so each
peer applies exactly the same decisions in exactly the same order.

Use it for coordination decisions (turn order, locks, durable workflow steps)
where two peers disagreeing would be a correctness bug.

Formally, `kuilt-raft` implements the Raft consensus algorithm for Kotlin
Multiplatform.

## Transport independence

`RaftTransport` is a plain interface. Raft runs over your own messaging layer without needing any other kuilt module. The bundled `SeamRaftTransport` wraps a kuilt `Seam` for the common case:

```kotlin
val seam: Seam = loom.host(Pattern("raft-cluster"))
val transport = SeamRaftTransport(seam)
```

Implement `RaftTransport` directly to plug in WebRTC, gRPC, or anything else.

## What's included

| Feature | Notes |
|---------|-------|
| Leader election + PreVote | PreVote prevents disruptive elections from partitioned nodes |
| Log replication | Leader replicates entries; followers commit once quorum confirms |
| Log compaction | Publish a snapshot into `node.snapshots`; Raft discards covered log entries and catches lagging peers up with chunked `InstallSnapshot` |
| Dynamic membership | `changeMembership()` — add/remove voters via joint consensus (§6) or simple config for learner-set-only changes |
| Linearizable reads | `readIndex()` confirms a voter quorum at current term before returning a safe read index (§3.6/§3.7); no log write required |
| Graceful leadership transfer | `transferLeadership(target)` sends `TimeoutNow` to the target so it wins the next election without waiting for a timeout (§3.10) |
| Propose forwarding | Any peer can call `propose()` — non-leaders forward the proposal to the current leader and await commit, so callers need not track who the leader is |
| Learner nodes | Non-voting replicas that receive all entries but never lead |

## Quick start

```kotlin
val cluster = ClusterConfig.ofVoters(listOf(NodeId("a"), NodeId("b"), NodeId("c")))
val storage = InMemoryRaftStorage()   // use persistent storage in production

val node: RaftNode = scope.raftNode(cluster, transport, storage)

// Apply committed entries on every node:
scope.launch {
    node.committed.collect { committed ->
        when (committed) {
            is Committed.Entry   -> applyToStateMachine(committed.entry.command)
            is Committed.Install -> resetStateMachineTo(committed.snapshot.state)
        }
    }
}

// Propose from any peer — forwarded to the leader if needed:
val entry: LogEntry = node.propose("set x=1".encodeToByteArray())
```

`raftNode` is a `CoroutineScope` extension — the node's lifetime is tied to the scope.

## Turn-based game facade

`kuilt-game`'s `TurnSequencer` wraps a `RaftNode` and hides Raft mechanics
behind a typed action/committed-stream API, so game code can focus on domain
rules instead of consensus plumbing.

## Storage

`InMemoryRaftStorage` is provided for tests. Production deployments need a durable implementation (SQLite, IndexedDB, or similar) to survive restarts.
