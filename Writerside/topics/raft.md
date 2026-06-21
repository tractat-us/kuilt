# Consensus and Leader Election

Use `kuilt-raft` when every peer must agree on what happens next, in exactly the same order.

At a high level, this gives your session one current leader and a shared decision log that every node applies in lockstep.

Use it for turn order, locks, and durable workflow steps — situations where peers disagreeing would be a correctness bug, not just an inconvenience.

Under the hood this is the **Raft** consensus algorithm, a well-studied approach to leader election and log replication across a cluster of peers.

## Transport independence

`RaftTransport` is a plain interface. Raft can run over your own messaging
layer without any other kuilt module. The bundled `SeamRaftTransport` wraps a
kuilt `Seam` for the common case:

```kotlin
val seam: Seam = loom.host(Pattern("raft-cluster"))
val transport = SeamRaftTransport(seam)
```

Implement `RaftTransport` directly to plug in WebRTC, gRPC, or another
transport.

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

`raftNode` is a `CoroutineScope` extension, so the node's lifetime is tied to
the scope.

## Turn-based game facade

`kuilt-game` provides two layers on top of `RaftNode`:

**Bootstrap — `gameHost` / `gameJoin` / `gameNode`**

These three functions are the recommended entry point. They wrap a plain
`Seam`, set up internal multiplexing (Raft channel + app-envelope channel over
one connection), and return a `GameSession`:

- `gameHost(seam, peerCount)` — one peer per session; detects duplicate hosts,
  bootstraps a singleton-voter cluster, and admits each joiner until the roster
  reaches `peerCount`.
- `gameJoin(seam)` — all other peers; announces itself, then waits to be promoted
  from learner to voter.
- `gameNode(seam, voterIds)` — roster-given bootstrap: every peer passes the same
  voter set (known up-front, e.g. from matchmaking) and Raft elects the leader
  symmetrically, with no appoint-the-host step.

`GameSession` carries the `RaftNode` (for consensus) and `appChannel(name)`
(for best-effort app traffic such as chat, cursors, or voice signalling,
sharing the same fabric without a second connection):

```kotlin
```
{ src="../../kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt" include-symbol="sampleGameHostJoin" }

**`TurnSequencer`** wraps a `RaftNode` and hides Raft mechanics behind a typed
action/committed-stream API, so game code can focus on domain rules.

## Storage

`InMemoryRaftStorage` is provided for tests. Production deployments need a durable implementation (SQLite, IndexedDB, or similar) to survive restarts.
