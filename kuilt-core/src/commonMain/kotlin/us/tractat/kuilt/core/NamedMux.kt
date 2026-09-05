package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * String-keyed multiplexer over a [Seam] — the unbounded-namespace sibling of [MuxSeam].
 *
 * Produces independent [Seam] views — one per [channel] name — that share a single
 * upstream collection of [delegate]'s [Seam.incoming]. This satisfies the kuilt contract
 * that [Seam.incoming] is **single-collection**: only [NamedMux] ever collects from
 * [delegate]; each channel view subscribes to the internally-shared flow.
 *
 * Where [MuxSeam] tags frames with a single byte (a hard ceiling of 256 channels, suited to
 * a fixed handful of internal channels), [NamedMux] tags frames with a UTF-8 name, giving an
 * effectively unbounded application namespace. The two compose by nesting: a [MuxSeam] tag
 * can carry a whole [NamedMux] subtree, so only that subtree pays the wider header.
 *
 * ## Framing
 *
 * Every outbound frame is prefixed with `[len:1 byte][name UTF-8]`, where `len` is the
 * number of UTF-8 bytes in the name (`1..255`). Every inbound frame is filtered by its
 * decoded name and delivered to the matching channel view with the header stripped. Frames
 * whose name matches no channel view are silently discarded.
 *
 * ## Late-subscriber semantics
 *
 * The shared upstream is started with `replay = 0`. Frames emitted before a channel view
 * begins collecting are **not** replayed — this is best-effort delivery, suitable for
 * [Quilter]-grade consumers (which heal gaps via FullState + resend) but **not** for raw
 * at-least-once consumers, which must layer their own reliability. Identical caveat to
 * [MuxSeam].
 *
 * ## Channel identity
 *
 * [channel] is idempotent: calling it twice with the same [name][channel] returns the same
 * [Seam] instance. Thread-safe: concurrent [channel] calls are serialised by an internal
 * reentrant lock so the backing map is never raced.
 *
 * ## Per-channel close
 *
 * [ChannelView.close] stops delivery to **that view only** — its [Seam.incoming] completes
 * and further [Seam.broadcast]/[Seam.sendTo] calls become no-ops. The base [Seam] remains
 * live for all other channel views. The base closes only when the owner calls [closeBase]
 * (or closes the [delegate] directly). This deliberate owner-driven design avoids fragile
 * last-channel reference-counting and keeps lifecycle ownership clear: the entity that opened
 * the [delegate] is the entity that closes it.
 *
 * @param delegate the underlying [Seam] whose [Seam.incoming] this class owns.
 * @param scope a [CoroutineScope] for the shared upstream collector and per-view pipes.
 * @sample us.tractat.kuilt.core.sampleNamedMuxChannels
 */
public class NamedMux(
    delegate: Seam,
    scope: CoroutineScope,
) {
    private val base = MuxBase<String>(delegate, scope, NamedFraming)

    /**
     * Lifecycle of the underlying [delegate] [Seam]. Owners that re-weave a fresh base on tear
     * (e.g. [MuxClientLoom]) read this to detect that this generation is dead.
     */
    public val baseState: StateFlow<SeamState> get() = base.baseState

    /**
     * Returns a [Seam] view carrying only frames named [name].
     *
     * Outbound frames are prefixed with [name]'s UTF-8 length and bytes; inbound frames
     * named [name] are delivered with that header stripped.
     *
     * This method is idempotent: multiple calls with the same [name] return the same [Seam]
     * instance. Thread-safe.
     *
     * @throws IllegalArgumentException if [name]'s UTF-8 encoding is empty or exceeds 255 bytes.
     */
    public fun channel(name: String): Seam {
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size in 1..NamedFrame.MAX_NAME_BYTES) {
            "channel name must encode to 1..${NamedFrame.MAX_NAME_BYTES} UTF-8 bytes, was ${nameBytes.size}"
        }
        return base.channel(name)
    }

    /**
     * Closes the underlying [delegate] [Seam].
     *
     * Call this when you are done with the mux entirely and want to tear down
     * the shared socket. Individual channel views are closed via [Seam.close] on
     * the view itself; that does **not** close the base — only this method does.
     */
    public suspend fun closeBase(reason: CloseReason = CloseReason.Normal): Unit = base.closeBase(reason)
}

/**
 * Name framing: the `[len:1 byte][name UTF-8]` header of [NamedFrame]. The name is encoded to
 * bytes once per channel (here in [forKey]) so the send/receive path never re-encodes it.
 */
private object NamedFraming : MuxFraming<String> {
    override fun forKey(key: String): ChannelFraming = object : ChannelFraming {
        private val nameBytes = key.encodeToByteArray()

        /** The `[len:1][name UTF-8]` header, on every frame. */
        override val overheadBytes: Int = NamedFrame.headerBytesFor(nameBytes)

        override fun wrap(payload: ByteArray): ByteArray = NamedFrame.encode(nameBytes, payload)

        override fun strip(swatch: Swatch): Swatch = NamedFrame.strip(swatch)

        override fun belongsTo(swatch: Swatch): Boolean = NamedFrame.belongsTo(nameBytes, swatch)
    }
}
