package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

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
    private val delegate: Seam,
    private val scope: CoroutineScope,
) {
    /**
     * A single shared subscription on [delegate.incoming]. All channel views subscribe to
     * this rather than [delegate] directly, ensuring exactly one collection of the
     * underlying seam.
     */
    private val sharedIncoming = delegate.incoming
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 0)

    /**
     * Lifecycle of the underlying [delegate] [Seam]. Owners that re-weave a fresh base on tear
     * (e.g. [MuxClientLoom]) read this to detect that this generation is dead.
     */
    public val baseState: StateFlow<SeamState> get() = delegate.state

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
        require(nameBytes.size in 1..NamedFrame.MAX_NAME_BYTES) {
            "channel name must encode to 1..${NamedFrame.MAX_NAME_BYTES} UTF-8 bytes, was ${nameBytes.size}"
        }
        return lock.withLock { channels.getOrPut(name) { ChannelView(nameBytes) } }
    }

    /**
     * Closes the underlying [delegate] [Seam].
     *
     * Call this when you are done with the mux entirely and want to tear down
     * the shared socket. Individual channel views are closed via [Seam.close] on
     * the view itself; that does **not** close the base — only this method does.
     */
    public suspend fun closeBase(reason: CloseReason = CloseReason.Normal): Unit = delegate.close(reason)

    private inner class ChannelView(
        private val nameBytes: ByteArray,
    ) : Seam {
        private val _closed = atomic(false)

        /**
         * Per-view delivery spool. Frames are piped from [sharedIncoming] via a
         * background coroutine; closing the spool completes [incoming].
         */
        private val spool = Spool<Swatch>(DeliveryPolicy.Reliable)

        init {
            scope.launch {
                sharedIncoming.filter { swatch -> NamedFrame.belongsTo(nameBytes, swatch) }.collect { swatch ->
                    spool.deliver(NamedFrame.strip(swatch))
                }
                spool.close()
            }
        }

        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state

        override val incoming: Flow<Swatch> = spool.incoming

        override suspend fun broadcast(payload: ByteArray) {
            if (_closed.value) return
            delegate.broadcast(NamedFrame.encode(nameBytes, payload))
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (_closed.value) return
            delegate.sendTo(peer, NamedFrame.encode(nameBytes, payload))
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
                spool.close()
            }
        }
    }
}
