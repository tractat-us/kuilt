package us.tractat.kuilt.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of [PeerLinkFactory] for use in tests and
 * integration harnesses. All [PeerLink] instances produced by the same
 * factory instance share a single in-memory mesh.
 *
 * Thread-safe: the shared mesh state is protected by a [Mutex]. Frame
 * delivery is channel-based (one [Channel] per link) so backpressure and
 * FIFO delivery are both preserved.
 *
 * Not a production transport — no discovery, no network, no serialization.
 * Intended to be the test bedrock for `:session-protocol` and every layer
 * above it.
 */
public class InMemoryPeerLinkFactory : PeerLinkFactory {
    private val mutex = Mutex()

    // Shared peer set: every link in the mesh observes this same StateFlow.
    private val _peers = MutableStateFlow<Set<TransportPeerId>>(emptySet())

    // Registry of active links by their selfId.
    private val links = mutableMapOf<TransportPeerId, InMemoryPeerLink>()

    // Monotonically increasing counter used to generate unique peer IDs.
    private var peerCounter = 0

    override suspend fun open(config: SessionConfig): PeerLink =
        mutex.withLock {
            val id = freshPeerId()
            val link = InMemoryPeerLink(id, this)
            links[id] = link
            _peers.update { it + id }
            link
        }

    override suspend fun join(advertisement: PeerAdvertisement): PeerLink =
        mutex.withLock {
            require(advertisement is InMemoryPeerAdvertisement) {
                "InMemoryPeerLinkFactory only joins InMemoryPeerAdvertisement, got ${advertisement::class}"
            }
            val id = freshPeerId()
            val link = InMemoryPeerLink(id, this)
            links[id] = link
            _peers.update { it + id }
            link
        }

    public val peers: StateFlow<Set<TransportPeerId>> = _peers.asStateFlow()

    internal suspend fun dispatch(
        sender: TransportPeerId,
        payload: ByteArray,
        recipient: TransportPeerId?,
    ) {
        mutex.withLock {
            val targets =
                if (recipient == null) {
                    links.keys.filter { it != sender }
                } else {
                    listOf(recipient)
                }
            for (targetId in targets) {
                val target = links[targetId] ?: continue
                val frame =
                    OpaqueFrame(
                        payload = payload,
                        sender = sender,
                        sequence = target.nextSequence(),
                    )
                target.deliver(frame)
            }
        }
    }

    internal suspend fun remove(id: TransportPeerId) {
        mutex.withLock {
            links.remove(id)
            _peers.update { it - id }
        }
    }

    internal fun isActive(id: TransportPeerId): Boolean = links.containsKey(id)

    private fun freshPeerId(): TransportPeerId {
        peerCounter++
        return TransportPeerId("peer-$peerCounter")
    }
}

/**
 * A [PeerAdvertisement] implementation for the in-memory transport. Since
 * the in-memory factory does not need network discovery, this carries only
 * the display name. The factory itself provides the mesh context.
 */
public data class InMemoryPeerAdvertisement(
    override val displayName: String,
    override val peerKey: String = displayName,
) : PeerAdvertisement

private class InMemoryPeerLink(
    override val selfId: TransportPeerId,
    private val factory: InMemoryPeerLinkFactory,
) : PeerLink {
    private val incomingChannel = Channel<OpaqueFrame>(capacity = Channel.UNLIMITED)
    private var closed = false
    private var sequenceCounter = 0L

    override val peers: StateFlow<Set<TransportPeerId>> = factory.peers

    override val incoming: Flow<OpaqueFrame> = incomingChannel.receiveAsFlow()

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        factory.dispatch(sender = selfId, payload = payload, recipient = null)
    }

    override suspend fun sendTo(
        peer: TransportPeerId,
        payload: ByteArray,
    ) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        factory.dispatch(sender = selfId, payload = payload, recipient = peer)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        factory.remove(selfId)
        incomingChannel.close()
    }

    internal fun nextSequence(): Long = ++sequenceCounter

    internal fun deliver(frame: OpaqueFrame) {
        if (!closed) {
            incomingChannel.trySend(frame)
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "PeerLink for $selfId is closed" }
    }
}
