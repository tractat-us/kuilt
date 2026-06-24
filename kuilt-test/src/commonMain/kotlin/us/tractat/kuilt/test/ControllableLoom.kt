package us.tractat.kuilt.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch

/**
 * A drop-in replacement for [us.tractat.kuilt.core.InMemoryLoom] with
 * **controllable per-peer delivery** for deterministic concurrency testing.
 *
 * By default every frame fans out immediately — identical to [us.tractat.kuilt.core.InMemoryLoom].
 * When a peer is *held*, outbound frames addressed to that peer are buffered locally and
 * not forwarded to its `incoming` until [releaseDelivery] or [deliverNext] is called.
 *
 * This lets tests script interleavings that the atomic `InMemoryLoom.dispatch` cannot
 * express — for example: peer A acts on local state while peer C's concurrent frame
 * is held, then release C's frames to observe late-delivery convergence.
 *
 * ## Control surface
 *
 * - [holdDelivery] — buffer all subsequent frames destined for [to].
 * - [releaseDelivery] — flush the hold queue for [to] in FIFO order and resume
 *   immediate delivery.
 * - [deliverNext] — flush exactly one buffered frame to [to]; hold mode stays active.
 * - [bufferedCount] — number of frames currently buffered for [to].
 *
 * ## Peers and IDs
 *
 * Peers join by calling [weave] (or the [host]/[join] shortcuts). The `selfId` for a
 * [Rendezvous.New] is taken from the [us.tractat.kuilt.core.Pattern]'s `displayName`
 * (or auto-generated as `peer-N` when blank). [Rendezvous.Existing] uses the tag's
 * `displayName` the same way.
 */
public class ControllableLoom(
    public val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Loom {

    private val mutex = Mutex()
    private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())
    private val seams = mutableMapOf<PeerId, ControllableSeam>()
    private var counter = 0

    /** Peers whose inbound queue is held. */
    private val held = mutableSetOf<PeerId>()

    /** Buffered (sender, payload) pairs per destination peer. */
    private val holdQueues = mutableMapOf<PeerId, ArrayDeque<Pair<PeerId, ByteArray>>>()

    override suspend fun weave(rendezvous: Rendezvous): ControllableSeam =
        mutex.withLock { newSeam(peerId(rendezvous)) }

    override fun availability(): FabricAvailability = FabricAvailability.Available

    // ── Control surface ───────────────────────────────────────────────────────
    // holdDelivery and bufferedCount are synchronous — they only touch the hold
    // bookkeeping structures, never the Spool.  releaseDelivery and deliverNext
    // are suspend because flushing the hold queue calls the suspending Spool.deliver.

    /** Start buffering frames destined for [to] rather than delivering them immediately. */
    public fun holdDelivery(to: PeerId) {
        held.add(to)
        holdQueues.getOrPut(to) { ArrayDeque() }
    }

    /**
     * Flush all buffered frames for [to] in FIFO order and resume immediate delivery.
     */
    public suspend fun releaseDelivery(to: PeerId) {
        held.remove(to)
        drainQueue(to)
    }

    /**
     * Flush exactly one buffered frame to [to]; hold mode remains active.
     *
     * @return `true` if a frame was delivered, `false` if the queue was empty.
     */
    public suspend fun deliverNext(to: PeerId): Boolean {
        val queue = holdQueues[to] ?: return false
        if (queue.isEmpty()) return false
        val (sender, payload) = queue.removeFirst()
        val delivery = snapshotDelivery(to, sender, payload) ?: return true
        delivery.first.deliver(delivery.second)
        return true
    }

    /** Number of frames currently buffered for [to]. */
    public fun bufferedCount(to: PeerId): Int = holdQueues[to]?.size ?: 0

    // ── Internal mesh ─────────────────────────────────────────────────────────

    internal suspend fun dispatch(sender: PeerId, payload: ByteArray, recipient: PeerId?) {
        // Snapshot sequence-assigned frames under the lock, then deliver outside so a
        // SUSPEND-policy backpressure suspension never holds the factory mutex.
        val deliveries: List<Pair<ControllableSeam, Swatch>> = mutex.withLock {
            val targets = recipientsFor(sender, recipient)
            targets.mapNotNull { target ->
                if (target in held) {
                    holdQueues.getOrPut(target) { ArrayDeque() }.addLast(sender to payload)
                    null
                } else {
                    snapshotDeliveryLocked(target, sender, payload)
                }
            }
        }
        for ((seam, frame) in deliveries) {
            seam.deliver(frame)
        }
    }

    internal suspend fun remove(id: PeerId) {
        mutex.withLock {
            seams.remove(id)
            _peers.update { it - id }
        }
    }

    internal val peersState: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun newSeam(id: PeerId): ControllableSeam {
        val seam = ControllableSeam(id, this, policy)
        seams[id] = seam
        _peers.update { it + id }
        return seam
    }

    private fun peerId(rendezvous: Rendezvous): PeerId =
        when (rendezvous) {
            is Rendezvous.New -> PeerId(rendezvous.pattern.displayName.ifBlank { freshId() })
            is Rendezvous.Existing -> PeerId(rendezvous.tag.displayName.ifBlank { freshId() })
        }

    private fun freshId(): String = "peer-${++counter}"

    private fun recipientsFor(sender: PeerId, recipient: PeerId?): List<PeerId> =
        if (recipient == null) seams.keys.filter { it != sender } else listOf(recipient)

    /** Assigns a sequence number and builds the [Swatch]; must be called under [mutex]. */
    private fun snapshotDeliveryLocked(
        target: PeerId,
        sender: PeerId,
        payload: ByteArray,
    ): Pair<ControllableSeam, Swatch>? {
        val seam = seams[target] ?: return null
        val frame = Swatch(payload = payload, sender = sender, sequence = seam.nextSequence())
        return seam to frame
    }

    /**
     * Builds a delivery pair for a single (target, sender, payload) triple without
     * holding the [mutex] — used by the control surface which runs outside [dispatch].
     */
    private fun snapshotDelivery(
        target: PeerId,
        sender: PeerId,
        payload: ByteArray,
    ): Pair<ControllableSeam, Swatch>? {
        val seam = seams[target] ?: return null
        val frame = Swatch(payload = payload, sender = sender, sequence = seam.nextSequence())
        return seam to frame
    }

    private suspend fun drainQueue(to: PeerId) {
        val queue = holdQueues[to] ?: return
        val deliveries = buildList {
            while (queue.isNotEmpty()) {
                val (sender, payload) = queue.removeFirst()
                val delivery = snapshotDelivery(to, sender, payload)
                if (delivery != null) add(delivery)
            }
        }
        for ((seam, frame) in deliveries) {
            seam.deliver(frame)
        }
    }
}

/**
 * A [Seam] produced by [ControllableLoom]. Delivery is controlled by the parent loom;
 * callers interact with it identically to any other [Seam].
 */
public class ControllableSeam internal constructor(
    override val selfId: PeerId,
    private val loom: ControllableLoom,
    policy: DeliveryPolicy,
) : Seam {

    private val spool = Spool<Swatch>(policy)
    private var closed = false
    private var sequenceCounter = 0L

    override val peers: StateFlow<Set<PeerId>> = loom.peersState

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    override val incoming: Flow<Swatch> = spool.incoming

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        loom.dispatch(sender = selfId, payload = payload, recipient = null)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self" }
        if (peer !in loom.peersState.value) throw PeerNotConnected(peer)
        loom.dispatch(sender = selfId, payload = payload, recipient = peer)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _state.value = SeamState.Torn(reason)
        loom.remove(selfId)
        spool.close()
    }

    internal fun nextSequence(): Long = ++sequenceCounter

    internal suspend fun deliver(frame: Swatch) {
        if (!closed) spool.deliver(frame)
    }

    private fun checkNotClosed() {
        check(_state.value !is SeamState.Torn) { "Seam for $selfId is closed" }
    }
}
