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
 * **One instance is exactly one flat mesh — hence one *concurrently* hosted room.**
 * A single [InMemoryLoom] has no notion of separate sessions: every seam it
 * weaves (whether via [host]/[Rendezvous.New] or [join]/[Rendezvous.Existing])
 * joins the *same* peer set and receives every broadcast on it. So the correct
 * shape is **one [host] plus any number of [join]s** — that is the whole mesh.
 *
 * Hosting a **second, concurrent** room on one instance (a second
 * [Rendezvous.New] while the first host seam is still live) is a misuse: the two
 * "rooms" would silently cross-admit each other's members over the shared mesh
 * rather than being isolated. To make that loud rather than silently corrupt, a
 * second concurrent [Rendezvous.New] throws [IllegalStateException]. For
 * multi-room tests, use one [InMemoryLoom] per room, or the `InMemoryRoomFabric`
 * helper in `:kuilt-test`.
 *
 * **Re-hosting after the prior host tears is allowed.** Once the hosting seam is
 * closed/removed, a fresh [Rendezvous.New] is permitted again — this models a
 * real fabric's re-host-after-drop and keeps client-side resume (`MuxClientLoom`
 * re-weaves its base after a tear) and dynamic ply re-attach (`CompositeLoom`
 * re-weaves a detached ply) working over the in-memory bedrock. The guard rejects
 * two *live* hosts, never a sequential re-weave. [Rendezvous.Existing] (joiners)
 * stays unlimited and never touches the guard.
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

    // A single flat mesh hosts one room at a time. Holds the PeerId of the live
    // host seam while one exists; null when no host is live. A second New while
    // this is non-null would silently cross-admit two concurrent rooms; it is
    // cleared when the host seam is removed, so a sequential re-host is allowed.
    private var hostId: PeerId? = null

    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New -> mutex.withLock {
                check(hostId == null) {
                    "InMemoryLoom is a single flat mesh — hosting two rooms on one instance cross-admits " +
                        "their members. Use InMemoryRoomFabric (:kuilt-test) for multi-room tests, or one " +
                        "InMemoryLoom per room."
                }
                newSeam().also { hostId = it.selfId }
            }
            is Rendezvous.Existing -> mutex.withLock {
                require(rendezvous.tag is InMemoryTag) {
                    "InMemoryLoom only joins InMemoryTag, got ${rendezvous.tag::class}"
                }
                newSeam()
            }
        }

    private fun newSeam(): InMemorySeam {
        val id = nextMeshPeerId()
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
            // The host seam tore/left: clear the guard so a sequential re-host
            // (client-side resume, dynamic ply re-attach) can weave a fresh New.
            if (id == hostId) hostId = null
        }
    }

    /**
     * Mint the next identity for a seam woven on THIS mesh — a readable ordinal, not [freshPeerId].
     *
     * The counter is deliberate, and is not the per-loom counter that [freshPeerId] exists to
     * replace. That defect was cross-*device* collision: two phones each minting `peer-1` and
     * colliding the instant they met. One [InMemoryLoom] is one in-process mesh whose frames never
     * leave it, so a value unique within the instance is unique everywhere it can be observed, and
     * the counter buys back something a UUID costs — a failing test in any module above this one
     * prints `peer-1`/`peer-2` rather than two indistinguishable hex blobs.
     *
     * Guarded by the factory [mutex]: both [weave] branches hold it across [newSeam].
     */
    private fun nextMeshPeerId(): PeerId {
        peerCounter++
        return PeerId("peer-$peerCounter")
    }
}

/**
 * A [Tag] implementation for the in-memory transport. Since
 * the in-memory factory does not need network discovery, this carries only
 * the session name. The factory itself provides the mesh context.
 */
public data class InMemoryTag(
    override val sessionName: String,
    override val peerKey: String = sessionName,
    override val roomKey: String? = null,
) : Tag

private class InMemorySeam(
    override val selfId: PeerId,
    private val factory: InMemoryLoom,
    policy: DeliveryPolicy,
) : Seam {
    private val spool = Spool<Swatch>(policy)

    // Monotonic: flipped exactly once, by close(). It is a StateFlow rather than a plain `var`
    // because `peers` below latches on it — and because an atomic compareAndSet is what makes
    // close() idempotent under a multi-threaded dispatcher, which a read-then-write was not.
    private val closed = MutableStateFlow(false)
    private var sequenceCounter = 0L

    /**
     * The shared mesh registry while this seam is live, and exactly `{ selfId }` once it has closed
     * (#1849, obligation from #1816). A torn fabric can reach nobody, so a decorator folding this
     * seam must not read a remote peer out of it, and `selfId` must not go missing either — which
     * it would if this stayed the raw registry, since `close()` removes this peer from it.
     *
     * A [LatchingStateFlow] rather than a mapped view: the registry is **shared**, so it keeps
     * changing after this seam tears, and a constant transform over it would re-publish
     * `{ selfId }` on every one of those changes. See that class's KDoc.
     */
    override val peers: StateFlow<Set<PeerId>> =
        LatchingStateFlow(source = factory.peers, latched = closed, terminal = setOf(selfId))

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
        // Publish the roster collapse FIRST. `peers` latches the instant this flips, so a consumer
        // woken by the terminal `Torn` below already reads `{ selfId }` and can never observe the
        // pre-close roster through a torn seam (#1816). The compareAndSet also makes the
        // close-once guard atomic rather than a read-then-write race.
        if (!closed.compareAndSet(expect = false, update = true)) return
        _state.value = Torn(reason)
        factory.remove(selfId)
        spool.close()
    }

    internal fun nextSequence(): Long = ++sequenceCounter

    internal suspend fun deliver(frame: Swatch) {
        if (!closed.value) spool.deliver(frame)
    }

    private fun checkNotClosed() {
        check(_state.value !is Torn) { "Seam for $selfId is closed" }
    }
}
