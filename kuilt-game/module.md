# Module kuilt-game

Turn-based game facade over `:kuilt-raft`. Provides two bootstrap paths and a
typed turn sequencer; hides all Raft machinery from application code.

## Bootstrap paths

### Roster-given — `gameNode`

All participating peers are known before the session starts (e.g. from matchmaking).
Every peer builds the identical `ClusterConfig.ofVoters` and calls `gameNode`; Raft's
own election picks the leader symmetrically — no pre-Raft coordination required.

```kotlin
val session = backgroundScope.gameNode(seam, voterIds, raftConfig = raftConfig)
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

#### Return policy — `ReturnPolicy`

`gameHost` accepts a `returnAt: ReturnPolicy` parameter (default `FullMembership`)
that controls when it returns to the caller:

- **`ReturnPolicy.FullMembership`** — suspends until all `peerCount` voters have
  joined. The classic "wait for the full lobby" behaviour.
- **`ReturnPolicy.Quorum`** — returns as soon as a majority (`peerCount / 2 + 1`)
  of voters are present so the game can start without the slowest peer. Background
  admission continues on the caller's scope until the roster reaches `peerCount`, so
  a latecomer is promoted whenever it connects, however late.

```kotlin
// Start with a majority; latecomers join in the background.
val host = backgroundScope.gameHost(seam, peerCount = 4, returnAt = ReturnPolicy.Quorum)
```

All three entry points return a `GameSession` — its `node` is the local `RaftNode`
(`host.node` is the leader; `joiner.node` an admitted follower). The caller passes a
**plain `Seam`** in every case — the entry points multiplex Raft traffic (channel tag 1),
lobby presence (channel tag 2, `gameHost`/`gameJoin` only) and the application-envelope
`NamedMux` (channel tag 3) internally via `MuxSeam`. Callers must not pre-mux.

## Application channels — `GameSession.appChannel`

Ride extra named application traffic (chat, cursors, voice signalling, …) over the **same
fabric** as consensus. `session.appChannel(name)` returns a `Seam` for that name, nested as
a `NamedMux` under the reserved app-envelope tag — so the app wire-layout is identical across
all three bootstrap paths, and there is no second connection. The application owns the entire
name namespace (no reserved names). Delivery is best-effort (`replay = 0`); layer your own
reliability if you need at-least-once.

```kotlin
val chat = session.appChannel("chat")
scope.launch { chat.incoming.collect { frame -> renderChat(frame.payload) } }
chat.broadcast(message.encodeToByteArray())
```

`GameSession.close()` is a hard local teardown — it stops the node's loops, then closes the
fabric (idempotent). It is **not** a graceful cluster departure; hand off leadership and/or
change membership first for that.

## Driving the game — `TurnSequencer`

`TurnSequencer<A>(node, serializer)` wraps a `RaftNode` with typed actions — pass
`session.node`. `propose(A)` submits an action from any node and suspends until a quorum
commits it (use `propose(A, requestId)` for cross-crash exactly-once); `events` delivers every
turn event in order on all nodes (leader and followers alike): committed actions as
`TurnEvent.Committed` and, with log compaction on, snapshot installs as `TurnEvent.Reset`. Each
`TurnEvent.Committed.indexed` carries a non-null `dedupKey` — fold it through a
`ClientSessionTable` to preserve exactly-once.

```kotlin
val game = TurnSequencer(session.node, Move.serializer())
val events: Flow<TurnEvent<Move>> = game.events
val entry: IndexedAction<Move> = game.propose(Move(row = 0, col = 0))
```

## Optimistic UI — `SpeculativeSequencer` and `SpeculativeGame`

`SpeculativeSequencer<S, A>` wraps a `TurnSequencer` and applies local actions
*optimistically* before a quorum commits them, then rolls back and replays if the
committed order differs from what was predicted.

```kotlin
val speculative = SpeculativeSequencer(
    sequencer = TurnSequencer(session.node, Move.serializer()),
    game = myGame,           // SpeculativeGame<GameState, Move>
    initialState = state0,
    scope = viewModelScope,
)

// Observe for UI — always up to date, including speculative moves:
speculative.speculativeState.collect { render(it) }

// Propose a local move (optimistically applied immediately, quorum-confirmed later):
try {
    speculative.propose(myMove)
} catch (e: LeadershipLostException) { /* retry */ }
```

`SpeculativeGame<S, A>` is the consumer-owned state-machine contract the sequencer
calls:

| Method | Role |
|--------|------|
| `apply(state, action): S` | Advance the state by one action — must be **pure and deterministic**. |
| `snapshot(state): S` | Capture an independent checkpoint (deep-copy if [S] is mutable). |
| `restore(snapshot): S` | Reinstate a snapshot before replaying the pending buffer. |
| `fromSnapshot(bytes): S` | Rebuild state from a Raft snapshot install — only needed with log compaction. |

**Constraints:** `apply` must be deterministic and pure — replay correctness depends on
it. With log compaction enabled, a snapshot install surfaces as `TurnEvent.Reset`: the
sequencer discards its pending buffer and rehydrates the authoritative state via
`fromSnapshot`, then folds later commits on top. Implement `fromSnapshot` for
compaction-enabled sessions; the default throws (fail-loud) the first time an install arrives.

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
