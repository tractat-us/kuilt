package us.tractat.kuilt.session

import kotlinx.coroutines.delay
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// Minimal, compile-checked snippets quoted verbatim by docs/agent-cookbook.md.
// Keep each function tiny and self-contained; the cookbook copies the body.

/**
 * Reconnect after a transport drop by presenting the saved [ResumeToken], instead of
 * re-joining fresh (which would reset the slot). Don't re-track the grace window yourself.
 */
public suspend fun resumeAfterDropSample(room: Room) {
    // After the admit handshake the joiner holds a reconnect credential — save it.
    val token: ResumeToken = room.resumeToken ?: return
    // ... transport drops; you redial the fabric and rebuild the room ...
    // Present the saved token to re-enter within the leader's grace window.
    when (room.resume(token)) {
        ResumeResult.Success -> Unit // back in the room; state resync follows
        ResumeResult.WindowClosed -> Unit // grace window elapsed — re-join fresh
        is ResumeResult.TokenInvalid -> Unit // wrong session — re-join fresh
    }
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
 */
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
                is FailureReason.Refused -> Unit // show reason.message (auth-expired / version, …)
            }
            else -> Unit
        }
    }
}
