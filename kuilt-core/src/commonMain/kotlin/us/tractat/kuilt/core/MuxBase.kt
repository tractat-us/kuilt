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
 * The per-channel framing strategy: how one channel view wraps outbound payloads and
 * recognises / strips its own inbound frames. Precomputed once per channel (in
 * [MuxFraming.forKey]) so the hot send/receive path never re-derives its header — e.g.
 * [NamedMux] encodes the channel name to bytes once, not on every [Seam.broadcast].
 */
internal interface ChannelFraming {
    /** Wrap [payload] with this channel's outbound header. */
    fun wrap(payload: ByteArray): ByteArray

    /** Remove this channel's header from [swatch] (zero-copy view). */
    fun strip(swatch: Swatch): Swatch

    /** True iff [swatch] is framed for this channel. */
    fun belongsTo(swatch: Swatch): Boolean
}

/** Maps a channel key [K] to the [ChannelFraming] used by that channel's view. */
internal fun interface MuxFraming<K> {
    fun forKey(key: K): ChannelFraming
}

/**
 * The shared multiplexer core behind [MuxSeam] (byte-keyed) and [NamedMux] (name-keyed).
 *
 * Owns the single shared subscription on [delegate]'s [Seam.incoming] (satisfying the
 * single-collection contract), the idempotent channel map, and the per-view [ChannelView].
 * Framing is injected via [MuxFraming] so the two public muxers differ only in how they tag
 * frames — everything structural (sharing, locking, spooling, per-view close) lives here once.
 */
internal class MuxBase<K>(
    private val delegate: Seam,
    private val scope: CoroutineScope,
    private val framing: MuxFraming<K>,
) {
    /**
     * A single shared subscription on [delegate.incoming]. All channel views subscribe to this
     * rather than [delegate] directly, ensuring exactly one collection of the underlying seam.
     */
    private val sharedIncoming = delegate.incoming
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 0)

    /** Lifecycle of the underlying [delegate] [Seam]. */
    val baseState: StateFlow<SeamState> get() = delegate.state

    private val lock = reentrantLock()
    private val channels = mutableMapOf<K, Seam>()

    /**
     * Returns the [Seam] view for [key], creating it on first request. Idempotent and
     * thread-safe: concurrent calls are serialised by an internal reentrant lock.
     */
    fun channel(key: K): Seam = lock.withLock { channels.getOrPut(key) { ChannelView(framing.forKey(key)) } }

    /** Closes the underlying [delegate] [Seam]. */
    suspend fun closeBase(reason: CloseReason): Unit = delegate.close(reason)

    private inner class ChannelView(private val framing: ChannelFraming) : Seam {
        private val _closed = atomic(false)

        /**
         * Per-view delivery spool. Frames are piped from [sharedIncoming] via a
         * background coroutine; closing the spool completes [incoming].
         */
        private val spool = Spool<Swatch>(DeliveryPolicy.Reliable)

        init {
            scope.launch {
                sharedIncoming.filter { swatch -> framing.belongsTo(swatch) }.collect { swatch ->
                    spool.deliver(framing.strip(swatch))
                }
                spool.close()
            }
        }

        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state

        /**
         * The base seam's live verdict, verbatim — the per-session counterpart to
         * [MuxClientLoom.capability], which forwards the same way on the pre-connect surface.
         *
         * Multiplexing changes how many logical sessions share a link, not which medium carries it
         * nor whether that medium is usable right now — so the base's capability *is* this view's
         * capability, in both halves. Inheriting [Seam]'s roleless [FabricAvailability.Unknown]
         * floor would be strictly worse than an un-established guess: it discards a verdict already
         * established one layer down, so a channel over a fabric with a real OS path observer says
         * "cannot tell" while the fabric underneath is answering confidently — including when that
         * answer is [FabricAvailability.Unavailable], which the floor launders into silence. The
         * [TransportCapability.roles] half matters just as much: a muxed ply defaulting to
         * `emptySet()` under-reports what a `CompositeSeam` role rollup can do (#1546).
         *
         * Reactive by delegation rather than by snapshot — the base's own [StateFlow] is handed
         * through, so a path change after this view was created is seen by the view's holders.
         */
        override val capability: StateFlow<TransportCapability> get() = delegate.capability

        override val incoming: Flow<Swatch> = spool.incoming

        override suspend fun broadcast(payload: ByteArray) {
            if (_closed.value) return
            delegate.broadcast(framing.wrap(payload))
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (_closed.value) return
            delegate.sendTo(peer, framing.wrap(payload))
        }

        override suspend fun close(reason: CloseReason) {
            if (_closed.compareAndSet(false, true)) {
                spool.close()
            }
        }
    }
}
