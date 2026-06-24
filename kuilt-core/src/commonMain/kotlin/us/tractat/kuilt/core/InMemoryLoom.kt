package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.SeamState.Torn
import us.tractat.kuilt.core.SeamState.Woven

/**
 * In-memory implementation of [Loom] for use in tests and
 * integration harnesses. All [Seam] instances produced by the same
 * factory instance share a single in-memory mesh.
 *
 * Thread-safe: the shared mesh state is protected by a [Mutex]. Frame
 * delivery is bounded and backpressured via one [Spool] per link, with
 * overflow behaviour chosen by [DeliveryPolicy].
 *
 * The suspending [deliver] call happens **outside** the factory mutex —
 * sequence numbers are assigned under the lock, then delivery is performed
 * after releasing it, so a `SUSPEND`-policy backpressure suspension never
 * holds the mutex.
 *
 * Not a production transport — no discovery, no network, no serialization.
 * Intended to be the test bedrock for `:session-protocol` and every layer
 * above it.
 */
public class InMemoryLoom(
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Loom {
    private val mutex = Mutex()

    // Shared peer set: every link in the mesh observes this same StateFlow.
    private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())

    // Registry of active links by their selfId.
    private val links = mutableMapOf<PeerId, InMemorySeam>()

    // Monotonically increasing counter used to generate unique peer IDs.
    private var peerCounter = 0

    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New -> mutex.withLock { newSeam() }
            is Rendezvous.Existing -> mutex.withLock {
                require(rendezvous.tag is InMemoryTag) {
                    "InMemoryLoom only joins InMemoryTag, got ${rendezvous.tag::class}"
                }
                newSeam()
            }
        }

    private fun newSeam(): InMemorySeam {
        val id = freshPeerId()
        val link = InMemorySeam(id, this, policy)
        links[id] = link
        _peers.update { it + id }
        return link
    }

    public val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    internal suspend fun dispatch(
        sender: PeerId,
        payload: ByteArray,
        recipient: PeerId?,
    ) {
        // Snapshot (target, sequenced-frame) pairs under the lock — sequence assignment stays
        // atomic and ordered — then deliver outside the lock so a SUSPEND-policy backpressure
        // suspension never holds the factory mutex.
        val deliveries: List<Pair<InMemorySeam, Swatch>> = mutex.withLock {
            val targetIds = if (recipient == null) {
                links.keys.filter { it != sender }
            } else {
                listOf(recipient)
            }
            targetIds.mapNotNull { targetId ->
                val target = links[targetId] ?: return@mapNotNull null
                target to Swatch(payload = payload, sender = sender, sequence = target.nextSequence())
            }
        }
        for ((target, frame) in deliveries) {
            target.deliver(frame)
        }
    }

    internal suspend fun remove(id: PeerId) {
        mutex.withLock {
            links.remove(id)
            _peers.update { it - id }
        }
    }

    internal fun isActive(id: PeerId): Boolean = links.containsKey(id)

    private fun freshPeerId(): PeerId {
        peerCounter++
        return PeerId("peer-$peerCounter")
    }
}

/**
 * A [Tag] implementation for the in-memory transport. Since
 * the in-memory factory does not need network discovery, this carries only
 * the display name. The factory itself provides the mesh context.
 */
public data class InMemoryTag(
    override val displayName: String,
    override val peerKey: String = displayName,
) : Tag

private class InMemorySeam(
    override val selfId: PeerId,
    private val factory: InMemoryLoom,
    policy: DeliveryPolicy,
) : Seam {
    private val spool = Spool(policy)
    private var closed = false
    private var sequenceCounter = 0L

    override val peers: StateFlow<Set<PeerId>> = factory.peers

    // In-memory fabric is immediately live — no async link establishment.
    private val _state = MutableStateFlow<SeamState>(Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    override val incoming: Flow<Swatch> = spool.incoming

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        factory.dispatch(sender = selfId, payload = payload, recipient = null)
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        if (peer !in factory.peers.value) throw PeerNotConnected(peer)
        factory.dispatch(sender = selfId, payload = payload, recipient = peer)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _state.value = Torn(reason)
        factory.remove(selfId)
        spool.close()
    }

    internal fun nextSequence(): Long = ++sequenceCounter

    internal suspend fun deliver(frame: Swatch) {
        if (!closed) spool.deliver(frame)
    }

    private fun checkNotClosed() {
        check(_state.value !is Torn) { "Seam for $selfId is closed" }
    }
}
