# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `ResumeToken` + `SeamRoom` resume | [Rejoin & reconnect](#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](#rejoin--reconnect) |
| a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets | `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`) | [Rejoin & reconnect](#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](#consensus--turns) |
| a heartbeat, an idle reaper, "is this peer still alive", "evict stale session" | `HeartbeatPartitionDetector` | [Liveness & presence](#liveness--presence) |
| "close a room nobody joined", "reap an abandoned table/lobby", "nobody ever showed up" | `SoloDeadlineDetector` | [Liveness & presence](#liveness--presence) |
| a "hold the seat open" / reconnect grace window on the host, a `pendingSeats` or `disconnectedAt` map | `JoinerReconnectController` | [Liveness & presence](#liveness--presence) |
| a "paused / reconnecting…" presence flag, a `lastSeen` map for greying out a player | `Room.events` + `Member.liveness` | [Liveness & presence](#liveness--presence) |
| that same "paused / reconnecting…" surface for a **game** (not a bare room), a `room.events` → game-presence adapter | `RoomGameSession.presence` via `gameOverRoom` | [Liveness & presence](#liveness--presence) |
| a last-write-wins register, a grow-only set/counter, an add/remove set, a version vector, "merge these two states" | the CRDT zoo (`LWWRegister`, `GSet`, `PNCounter`, `ORSet`, …) | [Replicated data](#replicated-data) |
| replicating a CRDT over a connection by hand | `Quilter` | [Replicated data](#replicated-data) |
| a `seenIds` set to skip already-handled messages | `GSet` / kuilt dedup | [Dedup](#dedup) |
| merging several mDNS/Multipeer discovery feeds into one lobby roster | `discoveryRoster` | [Discovery](#discovery) |

## Discovery

**Intent:** merge several `PeerDiscoverySource` feeds (mDNS, Multipeer, …) into one live roster for a lobby UI — "who can I currently see?"
**Primitive:** `discoveryRoster(sources, scope)` (`us.tractat.kuilt.core.discovery`). Folds `discoveries()` minus `departures()`, keyed on `Tag.peerKey`, into one `StateFlow<Set<Tag>>`. Don't hand-roll the merge.

It returns only **this peer's current best view** — not an agreement. It is **not** an election input: pick a host from `Seam.peers` once connected, never from this roster. And note the ghost caveat — a source whose `departures()` is the default (`emptyFlow()`) is add-only, so departed peers linger forever.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/discovery/DiscoverySamples.kt#sampleDiscoveryRoster -->
```kotlin
// One StateFlow the lobby UI renders directly — no hand-rolled merge.
val roster = discoveryRoster(listOf(mdns, multipeer), backgroundScope)
runCurrent()

mdnsPeers.emit(InMemoryTag("alice"))
mdnsPeers.emit(InMemoryTag("bob"))
runCurrent()
check(roster.value.map { it.peerKey }.toSet() == setOf("alice", "bob"))

// A departure removes the peer, keyed on Tag.peerKey.
mdnsGone.emit("alice")
runCurrent()
check(roster.value.map { it.peerKey }.toSet() == setOf("bob"))
```

## Rejoin & reconnect

**Intent:** rejoin / reconnect after a dropped connection; "hold the slot open" for a grace window.
**Primitive:** `ResumeToken` + the `SeamRoom` resume flow (`us.tractat.kuilt.session.partition`). Don't re-track the grace window yourself.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#resumeAfterDropSample -->
```kotlin
public suspend fun resumeAfterDropSample(room: Room) {
    // After the admit handshake the joiner holds a reconnect credential — save it.
    val token: ResumeToken = room.resumeToken ?: return
    // ... transport drops; you redial the fabric and rebuild the room ...
    // Present the saved token to re-enter within the leader's grace window.
    when (room.resume(token)) {
        ResumeResult.Success -> Unit // back in the room; state resync follows
        ResumeResult.WindowClosed -> Unit // grace window elapsed — re-join fresh
        ResumeResult.WindowNotYetOpen -> Unit // host hasn't noticed the drop yet — retry shortly
        is ResumeResult.TokenInvalid -> Unit // wrong session — re-join fresh
    }
}
```

**Intent:** decide whether a host's *refusal* is worth retrying, instead of string-matching the reason.
**Primitive:** `RejectCode` on `FailureReason.Refused` / `AdmissionFailure.Rejected` (`us.tractat.kuilt.session.admit`) — branch on the code and treat anything unrecognised as retryable.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#classifyRejectCodeSample -->
```kotlin
public fun classifyRejectCodeSample(reason: FailureReason.Refused): Boolean =
    when (reason.code) {
        // Terminal: the window closed, the credential can never validate here, or the two peers
        // speak incompatible protocol versions (retrying a version you don't support is futile).
        RejectCode.ResumeWindowExpired, RejectCode.ResumeTokenInvalid,
        RejectCode.RoomMismatch, RejectCode.ProtocolMismatch,
        -> false
        // Transient: the host hasn't opened the window yet (the fast-reconnect race).
        RejectCode.ResumeWindowNotYetOpen -> true
        // Anything else, including a code this build has never heard of.
        else -> reason.code.retryable
    }
```

**Intent:** retry with back-off after a failed dial.
**Primitive:** `core.util.ExponentialBackoff` — don't hand-roll a `listOf(1.s, 5.s, 30.s)` delay table.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#retryWithBackoffSample -->
```kotlin
public suspend fun retryWithBackoffSample(random: Random, dial: suspend () -> Boolean) {
    val backoff = ExponentialBackoff(base = 1.seconds, cap = 30.seconds, random = random)
    var attempt = 0
    while (!dial()) {
        delay(backoff.delay(attempt++)) // full-jitter; decorrelates simultaneous retriers
    }
}
```

**Intent:** drive a "reconnecting…" banner, or decide "give up and show an error", from the reason kuilt already observed.
**Primitive:** `MembershipEvent.Partitioned.reason` (`ReconnectReason`) and `HostLost.reason` (`FailureReason`) — don't re-derive your own transient/unrecoverable classification.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#reconnectBannerSample -->
```kotlin
public suspend fun reconnectBannerSample(room: Room) {
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.Partitioned -> when (event.reason) {
                ReconnectReason.LinkTimeout, ReconnectReason.TransportClosed -> Unit // "Reconnecting…"
                ReconnectReason.Backpressure -> Unit // "Connection congested…"
            }
            is MembershipEvent.HostLost -> when (val reason = event.reason) {
                FailureReason.WindowExpired -> Unit // "Lost the host — rejoin"
                FailureReason.Unrecoverable -> Unit // "Can't reconnect — return to lobby"
                is FailureReason.Refused -> Unit // branch on reason.code; reason.message is for logs
            }
            else -> Unit
        }
    }
}
```

## Replicated data

Need a value that stays in sync across peers with no server to arbitrate conflicts? Don't
hand-roll merge logic — the CRDT zoo already has a lattice for the shape you need, and
`Quilter` already knows how to ship deltas over a `Seam`. The five below cover the cases
that come up constantly; the full 14-type zoo (`GCounter`, `TwoPhaseSet`, `MVRegister`,
`LWWMap`, `ORMap`, `BoundedCounter`, `Rga`, `Causal`, `JsonCrdt`, `EphemeralMap`, and more)
is documented in [`Writerside/topics/crdt-overview.md`](../Writerside/topics/crdt-overview.md).

**Intent:** a shared value where the most recent write wins, converging across peers with no server to arbitrate.
**Primitive:** `LWWRegister` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWRegister -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val left = LWWRegister.empty<String>().set(a, timestamp = 1L, value = "v1")
val right = LWWRegister.empty<String>().set(b, timestamp = 2L, value = "v2")

check(left.piece(right).value == "v2")  // ts=2 wins
check(right.piece(left).value == "v2")  // commutative
```

**Intent:** a set that only ever grows — completed tasks, acknowledged events, registered participants — with no remove needed and no server to arbitrate.
**Primitive:** `GSet` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```

**Intent:** a shared counter that peers increment and decrement independently — a score, a budget, a tally — converging to the same net value with no coordinator.
**Primitive:** `PNCounter` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#samplePNCounter -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

var counter = PNCounter.ZERO
counter = counter.piece(counter.increment(a, 10))
counter = counter.piece(counter.decrement(b, 3))

check(counter.value == 7L)
```

**Intent:** a shared set where peers add and remove concurrently, and a concurrent re-add should beat a concurrent remove rather than silently vanishing.
**Primitive:** `ORSet` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSet -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Shared start: "alice" is present on both replicas.
val start = ORSet.empty<String>().add(a, "alice")

val alice = start.remove("alice")       // Alice concurrently removes
val bob = start.add(b, "alice")         // Bob concurrently re-adds

val merged = alice.piece(bob)
check(merged.contains("alice"))         // add-wins
```

**Intent:** replicating a CRDT live over a `Seam` by hand — collecting inbound deltas, merging them, broadcasting outbound deltas, and exposing the converged value as a `StateFlow`.
**Primitive:** `Quilter` (`us.tractat.kuilt.quilter`). Don't drive `Seam.incoming` and delta merge/broadcast yourself.

<!-- verbatim from kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt#sampleQuilterSetup -->
```kotlin
internal fun sampleQuilterSetup() = runTest(
    StandardTestDispatcher(),
    timeout = 5.seconds,
) {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("my-session"))

    val cfg = QuilterConfig(expectVirtualTime = true)
    val replicator = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
        scope = backgroundScope,
        config = cfg,
    )

    // Apply a mutation — the delta is broadcast to all current peers automatically.
    replicator.apply(replicator.state.value.inc(replicator.replica, 1L))

    // state is a StateFlow — always the current converged value.
    assertEquals(1L, replicator.state.value.value)
}
```

See [`crdt-quilter.md`](../Writerside/topics/crdt-quilter.md) for `Quilter`'s wire protocol,
late-joiner full-state sync, and scaling to many peers via `GossipSeam`.

## Liveness & presence

**Intent:** detect that a peer went silent / is no longer alive; "heartbeat", "lastSeen".
**Primitive:** `HeartbeatPartitionDetector` + `HeartbeatConfig` (`:kuilt-liveness`). Don't hand-roll a `while (true) { delay(); ping() }` loop.

<!-- verbatim from kuilt-liveness/src/commonSamples/kotlin/us/tractat/kuilt/liveness/AgentCookbookSamples.kt#detectSilentPeerSample -->
```kotlin
public suspend fun detectSilentPeerSample(
    link: Seam,
    peerId: PeerId,
    scope: CoroutineScope,
    clock: () -> Instant,
) {
    val detector = HeartbeatPartitionDetector(link, peerId, HeartbeatConfig(), clock)
    detector.start(scope)
    detector.events.collect { event ->
        when (event) {
            is PartitionEvent.PeerUnresponsive -> Unit // pause app processing; reason says why
            is PartitionEvent.PeerRecovered -> Unit // peer came back within the reconnect window
            is PartitionEvent.PeerLost -> Unit // reconnect window elapsed — vacate the slot
        }
    }
}
```

**Intent:** close a room/table/lobby that never filled; "idle-reap a session nobody joined", "nobody ever showed up", "expire an abandoned table".
**Primitive:** `SoloDeadlineDetector` + `SoloDeadlineEvent` (`:kuilt-liveness`). Don't hand-roll a `launch { delay(timeout); if (peers.size < 2) close() }`. It **emits**, it never closes — reaping policy stays yours.

<!-- verbatim from kuilt-liveness/src/commonSamples/kotlin/us/tractat/kuilt/liveness/AgentCookbookSamples.kt#reapNeverPairedRoomSample -->
```kotlin
public suspend fun reapNeverPairedRoomSample(
    link: Seam,
    scope: CoroutineScope,
    clock: Clock,
    closeRoom: suspend () -> Unit,
) {
    val detector = SoloDeadlineDetector(
        minimumMembers = 2, // this peer plus one — "never paired"
        deadline = 5.minutes,
        clock = clock,
        scope = scope,
    )
    // Feed it the roster on every change.
    scope.launch { link.peers.collect { detector.observeMembership(it) } }
    when (detector.events.first()) {
        is SoloDeadlineEvent.NeverPaired -> closeRoom() // nobody came; reaping policy is yours
        is SoloDeadlineEvent.Paired -> Unit // someone joined in time; the detector is done
    }
}
```

**Intent:** hold a dropped peer's **seat** open for a grace window instead of evicting it — "keep the slot", "reserved", "reconnect window", "don't kick them yet".
**Primitive:** `JoinerReconnectController` (`:kuilt-session`, `us.tractat.kuilt.session.partition`). It *is* the server-side seat-hold: `onPeerUnresponsive` opens the timed window, `tryResume` validates the returning peer's `ResumeToken` (right room, window still open, token not already used), and `events` reports `WindowOpened` / `Resumed` / `WindowExpired`. A `SeamRoom` host wires one for you — reach for this directly only when you own the host loop. Don't keep your own `pendingSeats` / `disconnectedAt` map.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#holdTheSeatOpenSample -->
```kotlin
public suspend fun holdTheSeatOpenSample(
    controller: JoinerReconnectController,
    dropped: PeerId,
    nowEpochMs: Long,
) {
    // The peer's link dropped: open (or refresh) its reconnect window rather than evicting.
    controller.onPeerUnresponsive(dropped, at = nowEpochMs)
    controller.events.collect { event ->
        when (event) {
            // The seat is reserved until event.expiresAt.
            is JoinerReconnectEvent.WindowOpened -> Unit
            // It came back in time — push an application-state snapshot to event.peerId.
            is JoinerReconnectEvent.Resumed -> Unit
            // Window elapsed; the seat is released and MembershipEvent.Left(PartitionExpired) follows.
            is JoinerReconnectEvent.WindowExpired -> Unit
        }
    }
}
```

> **Which one?** `SoloDeadlineDetector` answers *"did anyone ever join?"* — it disarms
> permanently on first pairing, so a room that fills and later empties emits nothing more.
> `HeartbeatPartitionDetector` answers *"is this peer, who **was** here, still alive?"*.
> Every `PartitionEvent` names a `peerId`; "nobody ever came" has no peer to name, which is
> why the never-paired case is a separate type rather than a `PartitionEvent` variant.

**Intent:** show a peer as **paused** (seat held) rather than gone — a greyed-out avatar, "reconnecting…", "waiting for player".
**Primitive:** `Room.events` + `Member.liveness` (`:kuilt-session`). `MembershipEvent.Partitioned` / `WindowOpened` / `Recovered` are the pause/resume pair, and the roster entry reads `Liveness.Partitioned` for as long as the seat is held. Don't build a `lastSeen` map on top of application traffic.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#observePausedPeersSample -->
```kotlin
public suspend fun observePausedPeersSample(room: Room) {
    // room.roster.value.filter { it.liveness == Liveness.Partitioned } is the same fact, pull-style.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.Partitioned -> Unit // grey the seat out — this peer's link dropped
            is MembershipEvent.WindowOpened -> Unit // its seat is held until event.expiresAt
            is MembershipEvent.Recovered -> Unit // it returned inside the window — un-grey it
            is MembershipEvent.Left -> Unit // gone for good: Normal (clean) or PartitionExpired
            else -> Unit
        }
    }
}
```

> These events mean the same thing on **every** member, whatever the topology underneath.
> Liveness is detected locally, which is enough on a mesh but blind on a star — so the host
> also fans out an authoritative `AdmitMessage.Paused` / `Unpaused`, and a `Farewell` when a
> window expires (#1557). Receipt is idempotent: a peer that detects the drop itself *and*
> receives the fan-out emits one event, not two.

**Intent:** the same pause/resume presence, but your session is a **game** bootstrapped over a room — "how do I know a player dropped?" should have one answer for a game and a room, not two.
**Primitive:** `RoomGameSession.presence` + `RoomGameSession.roster` (`:kuilt-game`). Bootstrap the game with `gameOverRoom(adoptedRoom)` and it returns a `RoomGameSession` whose `presence` **is** `room.events` and whose `roster` **is** `room.roster` — the exact `MembershipEvent` + `Member.liveness` vocabulary above. Don't hand-wire a `room.events` → game-presence adapter (and don't infer presence from Raft roster churn — that is where a "premature Resumed" lives). Presence here is **link liveness**: a `Resumed` a few seconds after a drop can be a legitimate link heal, so layer human "seated / away" state on top. `gameOverRoom` owns the room — tear both down with `RoomGameSession.close`, never `room.leave()`. See the compiled `sampleGameOverRoom` (`kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt`).

## Consensus & turns

**Intent:** a turn-based session where actions are proposed and become authoritative (or rejected), with a leader/host and a term — "propose", "authoritative", "host elected".
**Primitive:** `GameSession` + `TurnSequencer` (`:kuilt-game`) over `:kuilt-raft`. If you're building a `propose() → Proposed/Authoritative/Rejected` facade with a `HostElected(term)`, you're rebuilding this.

<!-- verbatim from kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt#sampleGameHostJoin -->
```kotlin
internal fun sampleGameHostJoin() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val hostSeam = loom.host(Pattern("tic-tac-toe"))
    val joinSeam = loom.join(InMemoryTag("player-2"))

    // Launch concurrently: gameHost suspends while admitting joiners;
    // gameJoin suspends until the host promotes it to voter.
    val hostDeferred = async {
        backgroundScope.gameHost(
            hostSeam,
            peerCount = 2,
            raftConfig = RaftConfig(expectVirtualTime = true),
            // clock is required (no wall-clock default); production callers pass the system clock.
            clock = { Clock.System.now() },
        )
    }
    val joinDeferred = async {
        backgroundScope.gameJoin(
            joinSeam,
            raftConfig = RaftConfig(expectVirtualTime = true),
        )
    }

    val host = hostDeferred.await()
    val joiner = joinDeferred.await()

    // Both nodes are voters. propose() may be called on any node —
    // followers forward to the leader transparently.
    val hostGame = TurnSequencer(host.node, Int.serializer())
    val joinerGame = TurnSequencer(joiner.node, Int.serializer())

    val move = hostGame.propose(1)
    assertEquals(1, move.action)

    // Any node may propose; the joiner's call is forwarded to the host (leader).
    val joinerMove = joinerGame.propose(2)
    assertEquals(2, joinerMove.action)

    // Ride an application channel (chat, cursors, …) over the same fabric as consensus.
    val incoming = async { joiner.appChannel("chat").incoming.first() }
    host.appChannel("chat").broadcast(byteArrayOf(0x68, 0x69)) // "hi"
    assertEquals(2, incoming.await().payloadSize)

    // Collect committed turns on any node in the game loop:
    // scope.launch {
    //     joinerGame.events.collect { event ->
    //         when (event) {
    //             is TurnEvent.Committed -> applyMove(event.indexed.index, event.indexed.action)
    //             is TurnEvent.Reset -> resetStateMachine(event.snapshot)
    //         }
    //     }
    // }

    // Tear the session down when done (stops the node, then closes the fabric).
    host.close()
    joiner.close()
}
```

## Dedup

**Intent:** skip a message/id you've already handled ("seenIds", "skip-if-exists").
**Primitive:** `GSet` (`:kuilt-crdt`) for a converging grow-only set, or kuilt's dedup key where you're inside a replicated log. Don't keep an ad-hoc `mutableSetOf<Long>()`.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```
