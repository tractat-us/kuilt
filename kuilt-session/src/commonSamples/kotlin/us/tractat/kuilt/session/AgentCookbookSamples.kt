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
 * Drive a reconnect banner off [Room.events] instead of inventing your own
 * connected / retrying / failed enum. [MembershipEvent.Partitioned] carries a
 * [ReconnectReason] (we're still trying) and [MembershipEvent.HostLost] a [FailureReason]
 * (we've stopped) — the one thing a banner has to decide is "keep waiting or give up".
 */
public suspend fun reconnectBannerSample(room: Room, show: (String) -> Unit) {
    room.events.collect { event ->
        when (event) {
            // Still trying: a window is open, so phrase the wait — never an error.
            is MembershipEvent.Partitioned -> show(
                when (event.reason) {
                    ReconnectReason.LinkTimeout -> "Connection is slow…"
                    ReconnectReason.Backpressure -> "Catching up…"
                    ReconnectReason.TransportClosed -> "Reconnecting…"
                },
            )
            is MembershipEvent.Recovered -> show("Connected")
            // Terminal: the reason says whether a fresh join is worth offering.
            is MembershipEvent.HostLost -> show(
                when (val reason = event.reason) {
                    FailureReason.WindowExpired -> "Lost the session — try joining again"
                    FailureReason.Unrecoverable -> "Can't reconnect to this session"
                    is FailureReason.Refused -> "The host refused us: ${reason.message}"
                },
            )
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
