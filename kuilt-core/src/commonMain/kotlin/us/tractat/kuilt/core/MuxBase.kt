package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    fun channel(key: K): Seam = lock.withLock { channels.getOrPut(key) { ChannelView(key, framing.forKey(key)) } }

    /** Closes the underlying [delegate] [Seam]. */
    suspend fun closeBase(reason: CloseReason): Unit = delegate.close(reason)

    /**
     * One logical session's view of the shared fabric.
     *
     * ## A view owns its own lifecycle; the base owns the base's (#2372, #949)
     *
     * [state] and [peers] used to be `get() = delegate.state` / `delegate.peers`, and that single
     * delegation was conflating **two** lifecycles. The base's must survive a per-channel close —
     * leaving one room cannot drop the socket every other room is riding, which is #949 and is not in
     * question. The **view's** is what the caller ends when it calls [close] on this seam, and it is
     * what every `Seam` consumer reads [state] to learn. Delegating both left a closed view reporting
     * the base's `Woven` forever, and advertising a roster of peers it would never deliver to again —
     * the lie [Seam.peers]' KDoc forbids, and a straight contradiction of the ungated-core
     * `SeamConformanceSuite.closeDrivesStateTornNormal`.
     *
     * So this view keeps its own [SeamStateGate] and its own roster, mirroring the base until one of
     * two things latches it terminal:
     *  - **its own [close]** — `Torn(reason)` here, base untouched and still `Woven` for its siblings;
     *  - **the base tearing** — `Torn` with the base's *own* reason, since a dead socket is a dead
     *    channel and a consumer waiting on this generation's death (every [MuxClientLoom] resume) must
     *    see it.
     *
     * Everything that is genuinely a property of the fabric rather than of this session —
     * [selfId], [plies], [capability], [maxPayloadBytes] — still reads through to the base.
     *
     * ## Consequence: a torn view refuses sends rather than swallowing them
     *
     * Publishing `Torn` subscribes this view to the rest of what `Torn` means. [Seam]'s contract is
     * explicit — *"Either call when `Torn`: throws [IllegalStateException]"* — so [broadcast] and
     * [sendTo] now refuse instead of silently dropping. The old swallow was defensible only while the
     * view also claimed to be `Woven`: a seam that has told its holder it is torn owes that holder the
     * failure rather than the appearance of a delivery.
     *
     * ## Thread safety
     *
     * [torn], the [gate] and [_peers] are written only under [viewLock], so "collapse the roster, then
     * latch `Torn`" is one atomic step — the ordering [Seam.peers] requires, not merely the settled
     * value. The lock is a real mutual-exclusion primitive rather than dispatcher confinement, it is a
     * leaf (nothing suspends inside it, and the gate's own lock composes safely underneath), and
     * [torn] is what lets one critical section cover a write to two different flows.
     */
    private inner class ChannelView(
        /** This channel's key, used only to name its pumps in a coroutine dump (see [pumpIn]). */
        private val key: K,
        private val framing: ChannelFraming,
    ) : Seam {

        /**
         * Per-view delivery spool. Frames are piped from [sharedIncoming] via a
         * background coroutine; closing the spool completes [incoming].
         */
        private val spool = Spool<Swatch>(DeliveryPolicy.Reliable)

        /** Guards [torn], [gate] and [_peers] together. See the class KDoc's thread-safety note. */
        private val viewLock = reentrantLock()

        /** Single-shot terminal marker. Mirrors [gate]'s latch, and additionally guards [_peers]. */
        private var torn = false

        private val gate = SeamStateGate(delegate.state.value)
        private val _peers = MutableStateFlow(delegate.peers.value)

        init {
            scope.launch {
                sharedIncoming.filter { swatch -> framing.belongsTo(swatch) }.collect { swatch ->
                    spool.deliver(framing.strip(swatch))
                }
                spool.close()
            }
            // A view minted over an already-dead base is born torn: `gate` was constructed with the
            // base's value, which publishes `Torn` WITHOUT latching it, so the next mirrored update
            // would overwrite the terminal state. Latch it here, before the pumps can run.
            (delegate.state.value as? SeamState.Torn)?.let { tear(it.reason) }
            delegate.state.pumpIn(
                scope = scope,
                onFailure = { _, failure -> tear(CloseReason.Error(failure)) },
                name = "mux-view-state[$key]",
            ) { next -> mirrorState(next) }
            delegate.peers.pumpIn(
                scope = scope,
                // A view that can no longer track the base's roster would go on advertising a stale
                // one — the same lie as a closed view's. Latch terminal instead.
                onFailure = { _, failure -> tear(CloseReason.Error(failure)) },
                name = "mux-view-peers[$key]",
            ) { next -> mirrorPeers(next) }
        }

        /** Mirror one base lifecycle value. The base's own `Torn` tears this view, reason and all. */
        private fun mirrorState(next: SeamState) {
            if (next is SeamState.Torn) {
                tear(next.reason)
            } else {
                viewLock.withLock { if (!torn) gate.update(next) }
            }
        }

        /** Mirror the base's roster, but never resurrect one this view has already collapsed. */
        private fun mirrorPeers(next: Set<PeerId>) = viewLock.withLock {
            if (!torn) _peers.value = next
        }

        /**
         * The single-shot terminal transition for this view — and only this view: the base is
         * deliberately untouched (#949).
         *
         * The roster collapse is inside the same critical section as the latch, so a consumer woken by
         * `Torn` can never read a roster that still names a peer this view will not deliver to.
         *
         * @return `true` for the one winning caller.
         */
        private fun tear(reason: CloseReason): Boolean {
            val won = viewLock.withLock {
                if (torn) return@withLock false
                torn = true
                _peers.value = setOf(delegate.selfId)
                gate.tear(reason)
                true
            }
            if (won) spool.close()
            return won
        }

        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()
        override val state: StateFlow<SeamState> = gate.state

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

        /**
         * The [SeamState.Torn] check comes first, as [Seam] requires it to everywhere: the tear is a
         * fact about *this* seam, and a caller told `PeerNotConnected` (or told nothing at all) would
         * reasonably retry against a channel that can no longer carry anything.
         *
         * The self-send refusal deliberately stays the base's: [selfId] reads through, so the base
         * holds the check against the very id this view publishes.
         */
        private fun checkLive() {
            check(state.value !is SeamState.Torn) {
                "this channel view is closed — its own close() latched Torn. The base seam may well " +
                    "still be live for its other channels (per-channel close), but this view cannot " +
                    "deliver: open a fresh channel rather than sending on a closed one."
            }
        }

        override suspend fun broadcast(payload: ByteArray) {
            checkLive()
            delegate.broadcast(framing.wrap(payload))
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            checkLive()
            delegate.sendTo(peer, framing.wrap(payload))
        }

        /**
         * Closes **this view** — its [incoming] completes, its [state] latches `Torn(reason)` and its
         * [peers] collapses to `{ selfId }`. The base [Seam] is untouched and stays live for every
         * other channel; only [closeBase] (or closing the delegate directly) ends that (#949).
         *
         * Idempotent: a second call is a no-op and does **not** rewrite the terminal reason.
         */
        override suspend fun close(reason: CloseReason) {
            tear(reason)
        }
    }
}
