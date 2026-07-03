package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Seam
import kotlin.time.Duration

/**
 * The number of settle steps a tap [pull][LogTapClient.pull] takes before treating the
 * replicated state as converged. Bounds the settle loop so it cannot hang on a
 * permanently-churning peer; the caller's `withTimeout` bounds it in wall-clock terms.
 */
internal const val SETTLE_ITERATIONS = 32

/**
 * Suspend until a peer other than this one is present on the [Seam]. Both tap clients wait
 * for the host to appear before expecting any replicated state to arrive.
 */
internal suspend fun Seam.awaitRemotePeer() {
    peers.first { peers -> peers.any { it != selfId } }
}

/**
 * Wait for [this] replicated-state flow to stop advancing, starting from [initial]. Each
 * step waits up to [step] for the next distinct state; a quiet step means settled. Bounded
 * by [SETTLE_ITERATIONS] and (via the caller's withTimeout) by the pull timeout. Works
 * under both real time and a virtual-time test scheduler (which auto-advances the step
 * delay when nothing else is runnable).
 */
internal suspend fun <T> Flow<T>.settle(step: Duration, initial: T): T {
    var current = initial
    repeat(SETTLE_ITERATIONS) {
        val next = withTimeoutOrNull(step) { first { it != current } } ?: return current
        current = next
    }
    return current
}
