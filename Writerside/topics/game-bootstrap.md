# Turn-based game bootstrap (kuilt-game)

`kuilt-game` provides a thin facade over `kuilt-raft` for turn-based games. It
handles consensus setup, internal multiplexing, and the game-loop abstraction so
you interact with typed actions rather than raw Raft machinery.

There are two bootstrap paths. Pick the one that fits how your session is established.

## Roster-given — `gameNode`

Use this path when every participating peer's identity is known before the session
starts — for example, after a matchmaking round that produces a fixed player list.

Every peer builds the identical voter set from `NodeId(seam.selfId.value)` values
and calls `gameNode`. Raft's own election picks the leader symmetrically — no
pre-Raft coordination step is required.

```kotlin
```
{ src="../../kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt" include-symbol="sampleGameNode" }

`gameNode` returns immediately once the node is running; it does not wait for
leadership or for other peers to connect. The `GameSession.node` is a `RaftNode` —
Raft's election will pick one leader from the voter set, and `propose()` forwards
transparently to whoever wins.

## Appoint-the-host — `gameHost` / `gameJoin`

Use this path when the roster is not fixed at session start — for example, a lobby
where peers join one by one. Exactly one peer calls `gameHost`; every other peer
calls `gameJoin`.

```kotlin
```
{ src="../../kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt" include-symbol="sampleGameHostJoin" }

### How it works

`gameHost` bootstraps a singleton-voter Raft cluster, becomes its leader, then
admits each connecting peer as learner → voter until the cluster reaches `peerCount`
voters.

`gameJoin` announces itself on the presence channel, starts as a non-voting learner,
and suspends until the host promotes it to voter. When `gameJoin` returns, the
`GameSession.node` is an admitted follower.

### Duplicate host detection

Exactly one peer per session must call `gameHost`. If two peers both call it
concurrently, `gameHost` uses lobby presence to detect the conflict. The lowest
`NodeId` declarant wins and proceeds as the canonical host; every other caller
throws `DuplicateHostException`. Because every peer has converged on the same
declared-host set before the arbitration runs, they agree on the winner
independently — a genuinely simultaneous race resolves to exactly one host, not
zero.

### Return policy

By default (`ReturnPolicy.FullMembership`), `gameHost` suspends until all
`peerCount` voters are present. Pass `ReturnPolicy.Quorum` to return as soon as a
majority (`peerCount / 2 + 1`) are present and start the game without the slowest
peer:

```kotlin
val host = backgroundScope.gameHost(
    seam,
    peerCount = 4,
    returnAt = ReturnPolicy.Quorum,
    raftConfig = RaftConfig(expectVirtualTime = true),
)
```

In `Quorum` mode, `gameHost` continues admitting the remaining voters in the
background on the caller's scope for the life of the session — a latecomer is
promoted whenever it connects, however late.

## Using the GameSession

All three bootstrap paths return a `GameSession` carrying:

- **`node`** — the local `RaftNode` (leader on the host, admitted follower on joiners).
- **`appChannel(name)`** — named application channels sharing the same fabric as
  consensus (see below).

Pass a **plain `Seam`** in every case. Internal multiplexing (Raft traffic on channel
tag 1, lobby presence on channel tag 2, app-envelope `NamedMux` on channel tag 3) is
handled inside the bootstrap functions — callers must not pre-mux the seam.

### Driving the game — TurnSequencer

Wrap `session.node` in a `TurnSequencer<A>` to interact with typed actions:

```kotlin
```
{ src="../../kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt" include-symbol="sampleTurnSequencer" }

`propose(action)` submits an action from any peer — non-leaders forward the proposal
to the current leader transparently. It suspends until a quorum commits the entry and
returns an `IndexedAction<A>` carrying the action and its log index.

`committed` is a `Flow<IndexedAction<A>>` that emits every committed action in order
on every node (leader and followers alike). Collect it in your game loop to drive
authoritative state.

### Application channels — `appChannel`

`session.appChannel(name)` returns a `Seam` for that name, carried over the same
fabric as consensus without a second connection. Use it for best-effort traffic that
lives alongside the game — chat, cursor positions, voice signalling:

```kotlin
val chat = session.appChannel("chat")
// On the receiver:
scope.launch { chat.incoming.collect { frame -> renderChat(frame.payload) } }
// On the sender:
chat.broadcast(message.encodeToByteArray())
```

Delivery is best-effort (`replay = 0`): a frame sent before the peer subscribes is
not replayed. The application owns the entire name namespace — there are no reserved
names.

### Optimistic UI — SpeculativeSequencer

For responsive UIs, wrap `TurnSequencer` in `SpeculativeSequencer` to apply local
actions optimistically before a quorum commits them, then roll back and replay if the
committed order differs:

```kotlin
```
{ src="../../kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt" include-symbol="sampleSpeculativeSequencer" }

`speculativeState` is a `StateFlow<S>` that is always current — the UI can observe
it directly. `SpeculativeGame<S, A>` is your state machine: implement `apply`,
`snapshot`, and `restore`. `apply` must be **pure and deterministic** — replay
correctness depends on it.

### Teardown

`GameSession.close()` stops the node's loops, then closes the fabric (idempotent).
This is a hard local teardown, not a graceful cluster departure. To leave cleanly,
hand off leadership with `RaftNode.transferLeadership()` and/or remove yourself from
membership with `RaftNode.changeMembership()` before closing.

## Single-collection constraint

After any bootstrap call wraps the seam, **do not collect `seam.incoming`** directly.
The internal `MuxSeam` is the sole consumer of the seam's incoming flow (single-collection
contract). A second collector races the Raft engine and drops messages silently.
