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
    /**
     * How many bytes [wrap] adds to a payload — the reservation a channel view holds back from the
     * budget it publishes (see [Seam.maxPayloadBytes], #2058).
     *
     * Constant for the life of a view, since the header is precomputed per channel: a byte tag
     * costs one byte, a name costs `1 + nameBytes.size`.
     */
    val overheadBytes: Int

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
         * The base seam's per-ply breakdown, verbatim (#2393).
         *
         * A pass-through, not a derivation: the plies of the base **are** the transport paths
         * carrying this channel. Multiplexing changes how many logical sessions share a link, not
         * how many links there are — it neither adds nor removes a path — so unlike
         * [maxPayloadBytes] there is nothing here to hold back.
         *
         * Inheriting [Seam]'s default was silent information loss. That default synthesises
         * `{ PlyId.Sole: state }`, so over a multi-ply
         * [us.tractat.kuilt.core.composite.CompositeSeam] a channel view reported a *single* rolled-up
         * entry and a holder could not tell a 3-ply composite from a single-ply fabric. Nothing red
         * on it, because the [Seam.plies] invariant — `state` equals the rollup of `plies.values` —
         * holds **trivially** for a one-entry map whose value *is* `state`; the same shape as the
         * capability defect above (#1546).
         *
         * Delegated by reference, so the breakdown stays live across ply churn under the base rather
         * than freezing at whatever was attached when this view was created.
         */
        override val plies: StateFlow<Map<PlyId, SeamState>> get() = delegate.plies

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

        /**
         * The base's budget less this channel's own header (#2058).
         *
         * Every send from this view is wrapped by [ChannelFraming.wrap] before it reaches the base,
         * so those bytes come out of the caller's allowance rather than being added to the wire.
         * Leaving [Seam]'s `null` in place was safe but lossy — it discarded a bound the fabric
         * underneath does know, and a consumer holding a channel view got no guidance where guidance
         * existed.
         *
         * `null` stays `null`: a base that names no ceiling has told this view nothing, and
         * inventing a number from it would turn "unknown" into a promise. Floored at zero, so a base
         * tighter than this channel's header publishes `0` — nothing fits, which is a legitimate
         * budget — rather than a negative one.
         *
         * Read through per call rather than captured: the base's number moves (a mesh reports the
         * minimum across its live links), so a snapshot taken when the view was created would hand
         * out a bound the fabric has since dropped below.
         */
        override val maxPayloadBytes: Int?
            get() = delegate.maxPayloadBytes?.let { (it - framing.overheadBytes).coerceAtLeast(0) }

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
