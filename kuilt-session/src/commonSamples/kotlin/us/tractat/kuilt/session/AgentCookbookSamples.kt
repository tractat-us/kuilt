package us.tractat.kuilt.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.election.ElectionLobby
import us.tractat.kuilt.session.election.ElectionOutcome
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// Minimal, compile-checked snippets quoted verbatim by docs/agent-cookbook.md.
// Keep each function tiny and self-contained; the cookbook copies the body.

/**
 * Chunk to the room's published budget instead of to the fabric's frame size.
 *
 * [Room.maxPayloadBytes] already holds back what the relay envelope costs, so a payload that
 * respects it survives whichever route the frame takes. Sizing to the fabric's own limit instead is
 * the #2047 trap: it fits until the roster diverges and the frame is relayed.
 */
public suspend fun chunkToTheRoomsBudgetSample(room: Room, peer: PeerId, blob: ByteArray) {
    var start = 0
    while (start < blob.size) {
        // Re-read per chunk, not once for the loop: the budget is a reading, not a lease. On a mesh
        // it is the minimum across live links, so a peer attaching over a tighter transport lowers
        // it under you mid-blob. null means "this fabric names no ceiling" — unknown, not
        // unbounded; floored at 1 because the budget is legitimately 0 on a fabric whose ceiling is
        // under the relay reservation.
        val budget = (room.maxPayloadBytes ?: DEFAULT_CHUNK_BYTES).coerceAtLeast(1)
        val end = minOf(start + budget, blob.size)
        // Index arithmetic, not `asSequence().chunked()` — the latter boxes every byte and builds
        // an ArrayList<Byte> per chunk. On a blob big enough to need chunking that is the point.
        // Past the budget, sendTo reports PayloadTooLarge (addressed sends do) while broadcast
        // drops with a log (lossy by contract) — neither surfaces the fabric's own oversize error.
        room.sendTo(peer, blob.copyOfRange(start, end))
        start = end
    }
}

/** A chunk size for a fabric that publishes no ceiling of its own. Small enough for any transport. */
private const val DEFAULT_CHUNK_BYTES: Int = 16 * 1024

/**
 * Read the per-session id both peers already agreed on, instead of minting one and replicating it.
 *
 * [Room.roomId] is settled in the admit handshake at zero extra traffic. Don't agree your own over
 * a side channel — and don't reach for the host's peer id either, which names the *device* and so
 * repeats across every room that device hosts.
 */
public suspend fun perSessionIdSample(room: Room) {
    // Null means "this joiner is not admitted yet", not "this room has no id" — so wait for the
    // value rather than sampling it. A host is non-null immediately and this returns at once.
    val id: RoomId = room.roomId.filterNotNull().first()
    // Safe as a durable key — a fresh room means a fresh id, including two games in a row from one
    // device and the games either side of an app kill.
    println("seat record key: ${id.value}/${room.selfId.value}")
}

/**
 * Host under an id **decided outside kuilt** — a lobby code, an invite link, a matchmaker-assigned
 * game id — instead of letting the factory mint one, so every member reads back the value your
 * other systems already key on.
 *
 * Supplying an id does **not** make a host restart resumable: a restarted host has no roster and no
 * reconnect-window registry, so a `ResumeToken` it accepts on identity grounds still cannot
 * complete. Cold-start rejoin is #1593.
 */
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

/**
 * Reconnect after a transport drop by presenting the saved [ResumeToken], instead of
 * re-joining fresh (which would reset the slot). Don't re-track the grace window yourself.
 *
 * The four arms below are the whole of [ResumeResult.JoinerOutcome] — the host's own verdicts are
 * a different half of the hierarchy and never reach a joiner as values, so a branch on one is a
 * compile error rather than dead code.
 */
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

/**
 * Decide whether a host's refusal is worth retrying. Branch on [RejectCode], not on the free-text
 * reason — and treat anything you don't recognise as retryable, because a peer that predates typed
 * codes (or a newer one that added its own) surfaces [RejectCode.Unknown].
 */
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

/**
 * Hold a dropped peer's seat open for a grace window instead of evicting it immediately —
 * the server/host side of the rejoin flow. [JoinerReconnectController] already owns the timer,
 * the single-use token check, and the eviction; don't keep your own `pendingSeats` map.
 */
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

/**
 * Show a peer as *paused* (its seat held) rather than gone, on every member — not just the host.
 * Don't track your own `lastSeen` map: [Room.events] and [Member.liveness] already say it.
 */
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

/**
 * Retry a failed dial with full-jitter exponential back-off. [random] is injected so tests can
 * seed it; production passes [Random.Default]. Don't hand-roll a fixed delay table.
 */
public suspend fun retryWithBackoffSample(random: Random, dial: suspend () -> Boolean) {
    val backoff = ExponentialBackoff(base = 1.seconds, cap = 30.seconds, random = random)
    var attempt = 0
    while (!dial()) {
        delay(backoff.delay(attempt++)) // full-jitter; decorrelates simultaneous retriers
    }
}

/**
 * Drive a reconnect banner / terminal-error decision from the reason kuilt already classifies,
 * instead of re-deriving your own transient/unrecoverable buckets. [ReconnectReason] says why the
 * link is down while a window is open; [FailureReason] says why the session ended for good.
 *
 * Check the `localFabric` tag **first**. Both events carry this peer's own
 * [Room.localFabric] as it stood when they were emitted, and when that is
 * [FabricAvailability.Unavailable] the event is not evidence about the peer it names — our own end
 * of the fabric was down, so their silence says nothing about them. Without that branch a joiner
 * whose own radio died renders "lost the host" (#1712).
 */
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

/**
 * Tell "**you** are offline" apart from "**they** are offline". Every other member of the presence
 * vocabulary names somebody else, so a peer that loses its own network blames its peers.
 * [Room.localFabric] is the level — a `StateFlow`, so a late reader cannot miss a drop — and
 * [MembershipEvent.LocalFabricLost] / [MembershipEvent.LocalFabricRestored] are the notifications
 * that it moved. Don't reach past [Room] into a transport-specific path monitor.
 */
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

/**
 * Handle all three ways a member's wait for the session can end (#1483).
 *
 * [ElectionLobby.awaitRoom] does not only return a [Room]. The peer that was hosting can leave the
 * roster, at which point this peer becomes the elected host — nothing has collapsed, and the
 * recovery is [ElectionLobby.start] on the **same** lobby, not a re-election.
 */
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
