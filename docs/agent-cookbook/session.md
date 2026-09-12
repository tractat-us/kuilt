# Session

A session is what keeps a group of people together in one virtual room even when someone's
phone drops its signal, a laptop is closed mid-game, or a connection just goes quiet for a
few seconds. This is where to look for getting a dropped player back into their seat, telling
who is still actually around versus who is really gone, and deciding — without any server
picking favourites — which device is in charge when a room full of peers first come together.

## Rejoin & reconnect

**Intent:** a per-game / per-session id both peers agree on — to key a durable `(session, device) → seat` record, scope a log, or name a table.
**Primitive:** `Room.roomId` (`us.tractat.kuilt.session`). Agreed in the admit handshake at zero extra traffic — don't mint your own and replicate it over a side channel, and don't use the host's peer id, which names the *device* and repeats across every room it hosts.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#perSessionIdSample -->
```kotlin
public suspend fun perSessionIdSample(room: Room) {
    // Null means "this joiner is not admitted yet", not "this room has no id" — so wait for the
    // value rather than sampling it. A host is non-null immediately and this returns at once.
    val id: RoomId = room.roomId.filterNotNull().first()
    // Safe as a durable key — a fresh room means a fresh id, including two games in a row from one
    // device and the games either side of an app kill.
    println("seat record key: ${id.value}/${room.selfId.value}")
}
```

**Intent:** the room's identity is decided *outside* kuilt — a lobby code, an invite link, a matchmaker-assigned game id — and every member must read back that exact value.
**Primitive:** `RoomFactory.host(pattern, roomId = …)`. Note what this is **not**: supplying an id does not make a *host restart* resumable. A restarted host has no roster and no reconnect-window registry, so a `ResumeToken` it accepts on identity grounds still cannot complete — cold-start rejoin is [#1593](https://github.com/tractat-us/kuilt/issues/1593), and reusing an id does not solve it.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#callerSuppliedRoomIdSample -->
```kotlin
public suspend fun callerSuppliedRoomIdSample(
    factory: RoomFactory,
    pattern: Pattern,
    lobbyCode: String,
): RoomId {
    val room = factory.host(pattern, roomId = RoomId(lobbyCode))
    // Read back off the room, not off the variable you passed in: the room is the thing joiners
    // agree with, and on a joiner this same property is how you learn the value at all.
    return room.roomId.value ?: error("a host room knows its id at construction")
}
```

**Intent:** rejoin / reconnect after a dropped connection; "hold the slot open" for a grace window.
**Primitive:** `ResumeToken` (`us.tractat.kuilt.session.partition`) presented to `Room.resume` — `Room.resumeToken` mints it, `Room` is the whole public surface. (`SeamRoom`, which this entry used to name, is `internal`; `SeamRoomFactory` builds it.) Don't re-track the grace window yourself.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#resumeAfterDropSample -->
```kotlin
public suspend fun resumeAfterDropSample(room: Room): Boolean {
    // After the admit handshake the joiner holds a reconnect credential — save it.
    val token: ResumeToken = room.resumeToken ?: return false
    // ... transport drops; you redial the fabric and rebuild the room ...
    // Present the saved token to re-enter within the leader's grace window.
    return when (val outcome = room.resume(token)) {
        ResumeResult.Success -> false // back in the room; state resync follows
        // The host answered, and said no. WHY is in the code, never in the message: an elapsed
        // window and a token for another room are terminal, a window the host has not opened
        // yet is not. See classifyRejectCodeSample.
        is ResumeResult.Refused -> outcome.code.retryable
        ResumeResult.TimedOut -> true // no reply within resumeTimeout — the host is unreachable now
        // We never asked: this room is already over (left, or the host was lost), or the frame
        // could not be sent. Re-join fresh.
        ResumeResult.WindowClosed -> false
    }
}
```

Those four arms are the whole of `ResumeResult.JoinerOutcome`. The host's own verdicts —
`WindowNotYetOpen`, `TokenInvalid` — are the other half of the hierarchy (`ResumeResult.HostVerdict`)
and never travel to a joiner as values; they arrive as the `RejectCode` on `Refused`. Branching on
one here is a **compile error**, which is how this entry stopped being able to advertise a
discrimination the API does not offer (#2364). Until then it listed both, the sample compiled, and a
consumer reading `WindowClosed -> // grace window elapsed` acted on a value that also meant "wrong
room" and "the host hasn't noticed your drop yet" — the last of which is *transient*, and re-joining
fresh is the wrong move for it.

**Intent:** decide whether a host's *refusal* is worth retrying, instead of string-matching the reason.
**Primitive:** `RejectCode` on `ResumeResult.Refused` / `FailureReason.Refused` / `AdmissionFailure.Rejected` (`us.tractat.kuilt.session.admit`) — branch on the code and treat anything unrecognised as retryable. The same classifier serves an in-flight resume and a session that already ended.

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

Check the `localFabric` tag **before** the reason. Both events carry this peer's own
`Room.localFabric` as it stood when they were emitted, and when that reads `Unavailable` for silence
**you observed yourself**, the event is not evidence about the peer it names — your own end was down,
so their quiet says nothing about them. Skip that check and a joiner whose own radio died renders
"lost the host", which is the bug this pattern used to ship.

One boundary worth knowing before you apply it broadly. `HostLost` is always something you observed,
so the tag always inverts attribution there. `Partitioned` is not always yours: on a joiner in a room
of three or more, the host relays "peer C paused", and that report is authoritative — it reached you
over a link that was working, so an `Unavailable` tag means only that your end was down when you
processed it, not that the report is wrong. Suppress it and C stays shown as present while the host
holds its seat open. The event carries no provenance field and `Room` exposes no host id, so you
cannot tell the two apart from the event alone; in a two-peer session it makes no difference, and in
a larger room scope the check to peers you watch yourself.

Precedence is otherwise readable straight off the event — you never have to correlate two streams by
timestamp. See [the "you are offline" entry](#liveness--presence) for the level behind the tag.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#reconnectBannerSample -->
```kotlin
public suspend fun reconnectBannerSample(room: Room) {
    room.events.collect { event ->
        // The tag inverts attribution only for silence *we* observed — our own detector, or our own
        // link tearing. `HostLost` is always that. `Partitioned` is not: on a joiner in a 3+-peer
        // room the host relays "peer C paused", and that report is host-authoritative — it arrived
        // over a link working well enough to deliver it, so an `Unavailable` tag there says our end
        // was down when we *processed* the report, not that the report is unfounded. Suppressing it
        // would leave C shown as present while the host holds its seat open.
        //
        // The event carries no provenance field, and `Room` exposes no host id, so a consumer cannot
        // tell the two apart from the event alone. In a two-peer session it does not matter (the only
        // peer you watch *is* the host). In a larger room, scope this to peers you observe yourself.
        val ourOwnEndWasDown = when (event) {
            is MembershipEvent.HostLost -> event.localFabric is FabricAvailability.Unavailable
            else -> false
        }
        when {
            // First branch, deliberately: never say "lost the host" when *we* are the ones offline.
            ourOwnEndWasDown -> Unit // "You're offline — check your connection"
            event is MembershipEvent.Partitioned -> when (event.reason) {
                ReconnectReason.LinkTimeout, ReconnectReason.TransportClosed -> Unit // "Reconnecting…"
                ReconnectReason.Backpressure -> Unit // "Connection congested…"
            }
            event is MembershipEvent.HostLost -> when (val reason = event.reason) {
                FailureReason.WindowExpired -> Unit // "Lost the host — rejoin"
                FailureReason.Unrecoverable -> Unit // "Can't reconnect — return to lobby"
                is FailureReason.Refused -> Unit // show reason.message (auth-expired / version, …)
            }
            else -> Unit
        }
    }
}
```

## Liveness & presence

People close laptops, step into lifts, and lose Wi-Fi. When that happens your app has to say
something — "reconnecting…", "waiting for player", or "you're offline" — and *which* of those it says
is most of the problem. Getting it wrong is very visible: a phone that drops its own signal and then
announces "everyone else vanished" has told the user the one thing that isn't true.

kuilt already notices the silence, holds someone's seat open while they are away, and — where the
platform can tell it — knows whether the connection that broke was theirs or yours. Read the state it
publishes instead of building a `lastSeen` map on top of your own traffic.

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
**Primitive:** `JoinerReconnectController` (`:kuilt-session`, `us.tractat.kuilt.session.partition`). It *is* the server-side seat-hold: `onPeerUnresponsive` opens the timed window, `onPeerRecovered` closes it when the peer comes back on its own, `tryResume` validates the returning peer's `ResumeToken` (right room, window still open, token not already used), and `events` reports `WindowOpened` / `Resumed` / `WindowExpired`. A `SeamRoom` host wires one for you — reach for this directly only when you own the host loop. Don't keep your own `pendingSeats` / `disconnectedAt` map.

> **If you implement this interface yourself, echo the detection instant.** Every `WindowOpened` and `WindowExpired` you emit must carry, as `detectedAt`, the exact `at` you were handed in `onPeerUnresponsive` for the drop that opened the window — unchanged, including on a later announcement that extends that same window. It names *which partition episode* the deadline is for, and the room drops any announcement whose episode is not the one it currently holds; a freshly-read clock would name *when you announced*, which is precisely the ambiguity it exists to remove, and every one of your refinements would be discarded (look for `room.window.stale-episode` at `debug`). Nothing checks this at compile time.

> **And disarm on `onPeerRecovered`.** Most peers come back without ever presenting a token — a blip is restored by the liveness detector alone, so `tryResume` is never reached and `onPeerRecovered` is the only thing that tells you the seat is occupied again. A `WindowExpired` you emit afterwards is not cosmetic: the room fans it out as an authoritative `Farewell`, so a healthy member is evicted from every roster but the host's, with no re-admit path behind it. Close the window there; do not route it through `expire`, which *is* an expiry. The room refuses such an expiry anyway (`windowExpired.suppressed … reason=recovered` at `info` is the tell), but that is a backstop, not the contract.

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
**Primitive:** `Room.roster` + `Member.liveness` (`:kuilt-session`) — the **level**, and what to key your UI on. `Room.events` (`MembershipEvent.Partitioned` / `WindowOpened` / `Recovered`) is the *notification* that it moved. Don't build a `lastSeen` map on top of application traffic.

The roster entry reads `Liveness.Partitioned(since, windowExpiresAt)` for as long as the seat is
held, so the countdown you display needs no event replay, and a late subscriber reads the current
state rather than missing the `Partitioned` that announced it. It reads the same way on **both**
roles — a joiner watching its host, and a host watching a joiner — so you do not need a different
strategy per role. Where the two surfaces can differ, the level is the one to trust: it is never
*staler* than an event, though during a rapid flap it can already be **ahead** of the one you are
handling. Two corollaries worth spelling out:

- **Don't key the un-grey on `Recovered` vs `Resumed`.** They differ by role and by recovery path,
  so either one alone leaves a real case hanging. The level clears on both.
- **A later `WindowOpened` for the same peer supersedes an earlier one.** A dropped link can
  re-open (refresh) its window, and the host also fans out its own authoritative deadline — so hold
  the latest `expiresAt` rather than assuming the first is final. Reading `windowExpiresAt` off the
  roster sidesteps the question entirely.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#observePausedPeersSample -->
```kotlin
public suspend fun observePausedPeersSample(room: Room) {
    // room.roster.value.filter { it.liveness is Liveness.Partitioned } is the same fact, pull-style —
    // and each Partitioned carries windowExpiresAt, so the countdown needs no event replay.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.Partitioned -> Unit // grey the seat out — this peer's link dropped
            // Held until event.expiresAt — but a later WindowOpened for the same peer supersedes
            // this one (a more authoritative deadline arrived), so keep the latest, not the first.
            is MembershipEvent.WindowOpened -> Unit
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

**Intent:** say "**you're** offline" instead of "everyone else vanished" — a "your connection dropped" banner, and deciding whether a peer's silence is really about *them*.
**Primitive:** `Room.localFabric` + `MembershipEvent.LocalFabricLost` / `LocalFabricRestored` (`:kuilt-session`). Every other member of the presence vocabulary names *somebody else*, so a device that loses its own network attributes the outage to its peers — and in a two-peer session the two cases are indistinguishable from peer-side observation alone. `localFabric` publishes the fact the transport already knew. Don't reach past `Room` into a transport-specific path monitor, and don't try to tell the cases apart by racing timestamps between two flows.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#localFabricBannerSample -->
```kotlin
public suspend fun localFabricBannerSample(room: Room) {
    // Bind the banner to the LEVEL, not to the edges. A StateFlow replays its current value to a late
    // collector, so this cannot miss a drop that happened before you subscribed, and it keeps the UI
    // reading the authoritative surface rather than a notification that may already be superseded.
    room.localFabric.collect { availability ->
        when (availability) {
            FabricAvailability.Available -> Unit // no banner
            is FabricAvailability.Unavailable -> Unit // "You're offline" — this room's fabric, not the device
            is FabricAvailability.Unknown -> Unit // kuilt cannot tell on this fabric — say nothing
        }
    }
    // The edges are for things a level cannot express — logging the transport's own words, or firing a
    // one-shot. Only transitions into Unavailable and into Available emit; a move into Unknown emits
    // nothing, because "we stopped being able to tell" is not a loss. Re-read the level when handling
    // one: under a rapid flap the level may already be ahead of the edge in your hand.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.LocalFabricLost ->
                Unit // event.reason is the transport's own words; room.localFabric.value is the truth now
            is MembershipEvent.LocalFabricRestored -> Unit // may arrive with no preceding Lost
            else -> Unit
        }
    }
}
```

Five things to know before you bind this to a UI:

- **On `:kuilt-websocket` you have to wire the observer — it is off by default.** Pass one to the
  loom: `KtorClientLoom(httpClient, connectivity = androidConnectivityObserver(context))` on Android,
  `browserConnectivityObserver()` on wasmJs. Without it the loom uses `UnobservedConnectivity` and
  every room over it reads `Unknown` forever, which is honest but useless for a banner. You own the
  observer's lifetime — `close()` it when the looms built from it are done. On the desktop JVM there
  is deliberately nothing to wire; don't synthesise reachability from socket state, because that
  reports the **relay's** health as the **device's** and those are different questions (the second is
  peer liveness — see the entry above).
- **It is session-scoped, never device-scoped.** A `Room` rides exactly one fabric, so this only ever
  means *"my end of **this room's** fabric."* A peer in two rooms over two fabrics has two independent
  values and neither speaks for the other; kuilt has no device-level registry. A room over a bonded
  `CompositeSeam` reports `Unavailable` only when **every** woven ply is down.
- **`Unknown` is a real third answer, and it is still a common one.** Only a fabric wired to an
  observer that watches its own reachability can give you a live yes or no; the lanes without one
  honestly report `Unknown`, meaning *kuilt cannot tell on this fabric*. Treat it as no information,
  never as either answer, and expect it rather than treat it as an error. It is also **per target,
  not per fabric**: `kuilt-websocket` watches Android's `ConnectivityManager` and the browser's
  `navigator.onLine`, while the desktop JVM has no portable observer and honestly says nothing — so
  one lane can answer on one platform and shrug on another. Which lanes have an observer changes as
  they are wired up one at a time, so read the flag rather than a list: a fabric's conformance test
  declares `reportsLiveCapability`, and
  [architecture.md](../architecture.md#reportslivecapability--fabrics-without-a-path-observer) explains
  what earns a `true`.
- **`Partitioned` and `HostLost` carry the same value as a tag**, captured at the instant they were
  emitted, which is what makes precedence readable from the stream. When the tag is `Unavailable` for
  silence *you* observed, that event is not evidence about the peer it names. `HostLost` always is
  yours; a `Partitioned` relayed by the host about a third peer is **not**, and stays authoritative —
  see [the reconnect-banner entry](#rejoin--reconnect) for where that boundary falls and why the event
  alone cannot tell you which side of it you are on.
- **The level is authoritative; the edges are notifications.** `Room.localFabric` is a `StateFlow`, so
  a late collector cannot miss a drop, while the events only announce transitions *into* `Unavailable`
  and *into* `Available` — a move into `Unknown` emits nothing. A `LocalFabricRestored` can therefore
  arrive with no preceding `LocalFabricLost` (a room whose fabric was already down when it was built),
  and the level can legitimately be *ahead* of the edge you are handling during a rapid flap. On a
  bonded `CompositeSeam` the **tag** is best-effort when every transport drops inside one dispatch
  window (#1778): re-read `Room.localFabric` at handling time if a decision must be certain.

## Host election & the lobby

**Intent:** several peers are connected and one of them has to host — and then that one walks out before the session starts.
**Primitive:** `SeamRoomFactory.electLobby(pattern)` → `ElectionLobby` (`:kuilt-session`). Every peer computes the same `electHost(peers)`; the elected one calls `start()`, the rest call `awaitRoom()`.

`awaitRoom()` returns a sealed **`ElectionOutcome`**, not a bare `Room`, and the case that catches people is `BecameHost`: the peer that was hosting left the roster, so this peer is now the elected host — the seam is healthy and the other members are still parked on it. **The recovery is `start()` on the SAME lobby.** Re-running `electLobby(...)` weaves a *fresh* seam and strands them; `leave()` first closes the shared seam and collapses them (#1483).

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#handleEveryElectionOutcomeSample -->
```kotlin
public suspend fun handleEveryElectionOutcomeSample(lobby: ElectionLobby): Room? =
    when (val outcome = lobby.awaitRoom(memberName = "Player 2")) {
        is ElectionOutcome.Adopted -> outcome.room
        // The hosting peer left and this peer is now the elected host, with the co-members still
        // parked in their own awaitRoom on the SAME seam — so they ack this freeze round at once.
        // Re-running electLobby(...) would weave a FRESH seam and strand them; calling leave() first
        // would close the shared seam and collapse them.
        ElectionOutcome.BecameHost -> lobby.start(memberName = "Player 2")
        // A genuine mid-2PC collapse: the co-electors are gone. Retryable — re-run electLobby(...).
        is ElectionOutcome.Torn -> null
    }
```

`awaitRoom` suspends indefinitely while the lobby is simply empty or still weaving in — that is a lobby doing its job, not a collapse, and it is why `host == selfId` on its own does **not** mean you were promoted (during weave-in you are momentarily the lowest id you can see). Cancel the call to stop waiting.
