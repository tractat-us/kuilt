# Module kuilt-game

Turn-based game facade over `:kuilt-raft`. Provides two bootstrap paths and a
typed turn sequencer; hides all Raft machinery from application code.

## Bootstrap paths

### Roster-given — `gameNode`

All participating peers are known before the session starts (e.g. from matchmaking).
Every peer builds the identical `ClusterConfig.ofVoters` and calls `gameNode`; Raft's
own election picks the leader symmetrically — no pre-Raft coordination required.

```kotlin
val node = backgroundScope.gameNode(seam, voterIds, raftConfig = raftConfig)
```

### Appoint-the-host — `gameHost` / `gameJoin`

Exactly one peer calls `gameHost`; every other peer calls `gameJoin`. The host
bootstraps a singleton-voter cluster, detects duplicate-host declarations via lobby
presence ([`DuplicateHostException`]), and admits each connecting peer as
learner → voter until the cluster reaches `peerCount` voters.

```kotlin
// On the host peer:
val host = backgroundScope.gameHost(seam, peerCount = 4)

// On every other peer:
val joiner = backgroundScope.gameJoin(seam)
```

Both calls suspend until the cluster reaches full membership, then return the local
`RaftNode`. The caller passes a **plain `Seam`** in both cases — `gameHost` and
`gameJoin` multiplex Raft traffic (channel tag 1) and lobby presence traffic (channel
tag 2) internally via `MuxSeam`. Callers must not pre-mux.

## Driving the game — `TurnSequencer`

`TurnSequencer<A>(node, serializer)` wraps a `RaftNode` with typed actions.
`propose(A)` submits an action from any node and suspends until a quorum commits it;
`committed` delivers every committed action in order on all nodes (leader and followers
alike).

```kotlin
val game = TurnSequencer(node, Move.serializer())
val committed: Flow<IndexedAction<Move>> = game.committed
val entry: IndexedAction<Move> = game.propose(Move(row = 0, col = 0))
```

## Single-collection constraint

After any of the three entry points wraps the seam, **do not collect `seam.incoming`**.
`MuxSeam` eagerly subscribes to the underlying seam and becomes its sole consumer
(ADR-034 single-collection). A second collector races the Raft engine and drops
messages, causing silent liveness failures.

## `DuplicateHostException`

Thrown by `gameHost` when another peer on the same session has already declared itself
host. Detected via lobby presence before Raft bootstraps; the conflicting peer fails
fast rather than entering an inconsistent state. Exactly one peer per session must call
`gameHost`.

## Virtual-time testing

Pass `RaftConfig(expectVirtualTime = true)` via the `raftConfig` parameter to suppress
the test-dispatcher warning and run under a `StandardTestDispatcher`. This is the *only*
supported path to virtual-time execution — the production overloads default to
`RaftConfig()` (`expectVirtualTime = false`). See `fastRaftConfig` in the test harness
(`HarnessSmokeTest.kt`) for a ready-made short-timeout configuration.
