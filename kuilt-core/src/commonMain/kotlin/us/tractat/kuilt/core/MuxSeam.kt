package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope

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
    delegate: Seam,
    scope: CoroutineScope,
) {
    private val base = MuxBase<Byte>(delegate, scope, ByteFraming)

    /**
     * Returns a [Seam] view carrying only frames tagged with [tag].
     *
     * Outbound frames are prefixed with [tag]; inbound frames tagged with [tag]
     * are delivered with the tag byte stripped.
     *
     * This method is idempotent: multiple calls with the same [tag] return the
     * same [Seam] instance. Thread-safe.
     */
    public fun channel(tag: Byte): Seam = base.channel(tag)

    /**
     * Closes the underlying [delegate] [Seam].
     *
     * Call this when you are done with the mux entirely and want to tear down
     * the shared socket. Individual channel views are closed via [Seam.close] on
     * the view itself; that does **not** close the base — only this method does.
     */
    public suspend fun closeBase(reason: CloseReason = CloseReason.Normal): Unit = base.closeBase(reason)
}

/** Byte-tag framing: a single leading [tag] byte identifies the channel. */
private object ByteFraming : MuxFraming<Byte> {
    override fun forKey(key: Byte): ChannelFraming = object : ChannelFraming {
        /** One leading tag byte, on every frame. */
        override val overheadBytes: Int = 1

        override fun wrap(payload: ByteArray): ByteArray {
            val tagged = ByteArray(payload.size + 1)
            tagged[0] = key
            payload.copyInto(tagged, destinationOffset = 1)
            return tagged
        }

        override fun strip(swatch: Swatch): Swatch = swatch.dropFirst(1)

        override fun belongsTo(swatch: Swatch): Boolean =
            swatch.payloadSize > 0 && swatch.byteAt(0) == key
    }
}
