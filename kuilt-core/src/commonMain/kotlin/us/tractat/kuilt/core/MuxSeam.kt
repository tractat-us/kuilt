package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

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
 * ## Per-channel close
 *
 * [ChannelView.close] stops delivery to **that view only** — its [Seam.incoming]
 * completes and further [Seam.broadcast]/[Seam.sendTo] calls become no-ops. The
 * base [Seam] remains live for all other channel views. The base closes only
 * when the owner calls [closeBase] (or closes the [delegate] directly). This
 * deliberate owner-driven design avoids fragile last-channel reference-counting
 * and keeps lifecycle ownership clear: the entity that opened the [delegate]
 * is the entity that closes it.
 *
 * @param delegate the underlying [Seam] whose [Seam.incoming] this class owns.
 * @param scope a [CoroutineScope] for the shared upstream collector and per-view pipes.
 */
public class MuxSeam(
    private val delegate: Seam,
    private val scope: CoroutineScope,
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

    /**
     * Closes the underlying [delegate] [Seam].
     *
     * Call this when you are done with the mux entirely and want to tear down
     * the shared socket. Individual channel views are closed via [Seam.close] on
     * the view itself; that does **not** close the base — only this method does.
     */
    public suspend fun closeBase(reason: CloseReason = CloseReason.Normal): Unit = delegate.close(reason)

    private fun taggedPayload(tag: Byte, payload: ByteArray): ByteArray {
        val tagged = ByteArray(payload.size + 1)
        tagged[0] = tag
        payload.copyInto(tagged, destinationOffset = 1)
        return tagged
    }

    private fun strippedPayload(swatch: Swatch): Swatch = swatch.dropFirst(1)

    private fun belongsTo(tag: Byte, swatch: Swatch): Boolean =
        swatch.payloadSize > 0 && swatch.byteAt(0) == tag

    private inner class ChannelView(private val tag: Byte) : Seam {
        private val _closed = atomic(false)

        /**
         * Per-view delivery channel. Frames are piped from [sharedIncoming] via a
         * background coroutine; closing this channel completes [incoming].
         */
        private val deliveryChannel = Channel<Swatch>(Channel.UNLIMITED)

        init {
            scope.launch {
                sharedIncoming.filter { swatch -> belongsTo(tag, swatch) }.collect { swatch ->
                    deliveryChannel.trySend(strippedPayload(swatch))
                }
                deliveryChannel.close()
            }
        }

        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state

        override val incoming: Flow<Swatch> = deliveryChannel.receiveAsFlow()

        override suspend fun broadcast(payload: ByteArray) {
            if (_closed.value) return
            delegate.broadcast(taggedPayload(tag, payload))
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (_closed.value) return
            delegate.sendTo(peer, taggedPayload(tag, payload))
        }

        /**
         * Closes this channel view only.
         *
         * After this call: [incoming] completes; [broadcast] and [sendTo] become no-ops.
         * The underlying [delegate] [Seam] remains live — call [closeBase] to tear that down.
         * Idempotent: subsequent calls are no-ops.
         */
        override suspend fun close(reason: CloseReason) {
            if (_closed.compareAndSet(false, true)) {
                deliveryChannel.close()
            }
        }
    }
}
