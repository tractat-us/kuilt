package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * N-way multiplexer over a [Seam].
 *
 * Produces independent [Seam] views — one per [channel] tag — that share a
 * single upstream collection of [delegate]'s [Seam.incoming]. This satisfies
 * the kuilt contract that [Seam.incoming] is **single-collection**: only
 * [MuxSeam] ever collects from [delegate]; each channel view subscribes to
 * the internally-shared flow.
 *
 * ## Framing
 *
 * Every outbound frame is prefixed with a 1-byte [channel] tag. Every inbound
 * frame is filtered by its first byte and delivered to the matching channel view
 * with the tag byte stripped. Frames with no matching channel are silently
 * discarded.
 *
 * ## Late-subscriber semantics
 *
 * The shared upstream is started with `replay = 0`. Frames emitted before a
 * channel view begins collecting are **not** replayed — this is best-effort
 * delivery, suitable for [Quilter] (which heals gaps via FullState +
 * resend) but **not** suitable for raw at-least-once consumers.
 *
 * ## Channel identity
 *
 * [channel] is idempotent: calling it twice with the same [tag] returns the
 * same [Seam] instance. Thread-safe: concurrent [channel] calls are serialised
 * by an internal reentrant lock so the backing map is never raced.
 *
 * @param delegate the underlying [Seam] whose [Seam.incoming] this class owns.
 * @param scope a [CoroutineScope] for the shared upstream collector.
 */
public class MuxSeam(
    private val delegate: Seam,
    scope: CoroutineScope,
) {
    /**
     * A single shared subscription on [delegate.incoming]. All channel views
     * subscribe to this rather than [delegate] directly, ensuring exactly one
     * collection of the underlying seam.
     */
    private val sharedIncoming = delegate.incoming
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 0)

    private val lock = reentrantLock()
    private val channels = mutableMapOf<Byte, Seam>()

    /**
     * Returns a [Seam] view carrying only frames tagged with [tag].
     *
     * Outbound frames are prefixed with [tag]; inbound frames tagged with [tag]
     * are delivered with the tag byte stripped.
     *
     * This method is idempotent: multiple calls with the same [tag] return the
     * same [Seam] instance. Thread-safe.
     */
    public fun channel(tag: Byte): Seam = lock.withLock { channels.getOrPut(tag) { ChannelView(tag) } }

    private fun taggedPayload(tag: Byte, payload: ByteArray): ByteArray {
        val tagged = ByteArray(payload.size + 1)
        tagged[0] = tag
        payload.copyInto(tagged, destinationOffset = 1)
        return tagged
    }

    private fun strippedPayload(swatch: Swatch): Swatch =
        swatch.copy(payload = swatch.payload.copyOfRange(1, swatch.payload.size))

    private fun belongsTo(tag: Byte, swatch: Swatch): Boolean =
        swatch.payload.isNotEmpty() && swatch.payload[0] == tag

    private inner class ChannelView(private val tag: Byte) : Seam {
        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state

        override val incoming: Flow<Swatch> = sharedIncoming
            .filter { swatch -> belongsTo(tag, swatch) }
            .map { swatch -> strippedPayload(swatch) }

        override suspend fun broadcast(payload: ByteArray) =
            delegate.broadcast(taggedPayload(tag, payload))

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) =
            delegate.sendTo(peer, taggedPayload(tag, payload))

        override suspend fun close(reason: CloseReason) = delegate.close(reason)
    }
}
