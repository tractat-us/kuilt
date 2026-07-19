package us.tractat.kuilt.session

import kotlinx.coroutines.delay
import us.tractat.kuilt.core.util.ExponentialBackoff
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
