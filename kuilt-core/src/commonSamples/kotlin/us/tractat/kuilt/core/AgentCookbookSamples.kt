package us.tractat.kuilt.core

// Minimal, compile-checked snippets quoted verbatim by docs/agent-cookbook/fabrics.md.
// Keep each function tiny and self-contained; the cookbook copies the body.

import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Send to everyone *else* — filter [Seam.selfId] out, or just broadcast.
 *
 * [Seam.peers] always contains [Seam.selfId] (the flow's initial-value invariant), so the uniform
 * loop self-sends on one iteration, and `sendTo(selfId, …)` is refused on every fabric — pinned by
 * `SeamConformanceSuite.sendToSelfIsRefused`. The refusal is deliberately not `PeerNotConnected`:
 * `selfId` *is* in the roster, so reporting the peer as absent would state something false.
 */
public suspend fun sendToEveryoneElseSample(seam: Seam, frame: ByteArray) {
    // Wrong — `peers` includes selfId, so this self-sends:
    //     seam.peers.value.forEach { seam.sendTo(it, frame) }
    // Either filter…
    seam.peers.value.filter { it != seam.selfId }.forEach { seam.sendTo(it, frame) }
    // …or just broadcast, which is what "everyone else" means at this layer.
    seam.broadcast(frame)
}

/**
 * Send a last frame and close, without a `delay` guessing how long the queue takes.
 *
 * Returns `true` when the frame left this device before the close. That is not the peer receiving it:
 * a sender that needs receipt has the peer answer.
 */
public suspend fun flushThenCloseSample(seam: Seam, peer: PeerId, lastFrame: ByteArray): Boolean {
    seam.sendTo(peer, lastFrame)
    val outcome = seam.flush(2.seconds)
    seam.close()
    return when (outcome) {
        FlushOutcome.Drained -> true
        // Not proven, not lost. This fabric cannot tell, the link stalled, or the link ended.
        FlushOutcome.Unsupported, FlushOutcome.TimedOut, is FlushOutcome.Failed -> false
    }
}

/**
 * The shape of `flush` for a queue a fabric owns: wait for it inside [flushWithin], which turns the
 * timeout into `TimedOut`, and a failure into `Failed`, and lets only the caller's own cancel through.
 */
public suspend fun flushWithinSample(timeout: Duration, queueDrained: CompletableDeferred<Unit>): FlushOutcome =
    flushWithin(timeout) {
        queueDrained.await()
        FlushOutcome.Drained
    }
