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
 * @param delegate the underlying [Seam] whose [Seam.incoming] this class owns.
 * @param scope a [CoroutineScope] for the shared upstream collector.
 */
public class NamedMux(
    private val delegate: Seam,
    scope: CoroutineScope,
) {
    /**
     * A single shared subscription on [delegate.incoming]. All channel views subscribe to
     * this rather than [delegate] directly, ensuring exactly one collection of the
     * underlying seam.
     */
    private val sharedIncoming = delegate.incoming
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 0)

    private val lock = reentrantLock()
    private val channels = mutableMapOf<String, Seam>()

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
        require(nameBytes.size in 1..MAX_NAME_BYTES) {
            "channel name must encode to 1..$MAX_NAME_BYTES UTF-8 bytes, was ${nameBytes.size}"
        }
        return lock.withLock { channels.getOrPut(name) { ChannelView(nameBytes) } }
    }

    private fun framedPayload(nameBytes: ByteArray, payload: ByteArray): ByteArray {
        val framed = ByteArray(1 + nameBytes.size + payload.size)
        framed[0] = nameBytes.size.toByte()
        nameBytes.copyInto(framed, destinationOffset = 1)
        payload.copyInto(framed, destinationOffset = 1 + nameBytes.size)
        return framed
    }

    /** Header length (`1 + nameLen`) of [swatch], or `-1` if it is too short to carry a name. */
    private fun headerLength(swatch: Swatch): Int {
        if (swatch.payload.isEmpty()) return -1
        val nameLen = swatch.payload[0].toInt() and 0xFF
        val header = 1 + nameLen
        return if (swatch.payload.size >= header) header else -1
    }

    private fun belongsTo(nameBytes: ByteArray, swatch: Swatch): Boolean {
        val header = headerLength(swatch)
        if (header < 0) return false
        if (header - 1 != nameBytes.size) return false
        for (i in nameBytes.indices) {
            if (swatch.payload[1 + i] != nameBytes[i]) return false
        }
        return true
    }

    private fun strippedPayload(swatch: Swatch): Swatch =
        swatch.copy(payload = swatch.payload.copyOfRange(headerLength(swatch), swatch.payload.size))

    private inner class ChannelView(
        private val nameBytes: ByteArray,
    ) : Seam {
        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state

        override val incoming: Flow<Swatch> = sharedIncoming
            .filter { swatch -> belongsTo(nameBytes, swatch) }
            .map { swatch -> strippedPayload(swatch) }

        override suspend fun broadcast(payload: ByteArray) =
            delegate.broadcast(framedPayload(nameBytes, payload))

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) =
            delegate.sendTo(peer, framedPayload(nameBytes, payload))

        override suspend fun close(reason: CloseReason) = delegate.close(reason)
    }

    private companion object {
        /** Maximum UTF-8 byte length of a channel name — the 1-byte length prefix caps it at 255. */
        const val MAX_NAME_BYTES = 255
    }
}
