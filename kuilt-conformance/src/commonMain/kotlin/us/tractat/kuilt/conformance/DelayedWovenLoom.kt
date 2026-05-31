package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag

/**
 * A test [Loom] whose [Seam]s start in [SeamState.Weaving] and only transition
 * to [SeamState.Woven] when [DelayedWovenSeam.markWoven] is called explicitly.
 *
 * This harness reproduces the radio-fabric timing window — the state where
 * `weave()` has returned but the fabric link is not yet live — in a fully
 * deterministic, in-memory environment. Use it to verify that consumers
 * (e.g. `SeamRoom`) correctly await [SeamState.Woven] before transmitting,
 * rather than assuming the seam is live immediately after `weave()` returns.
 *
 * ## Usage
 * ```kotlin
 * val loom = DelayedWovenLoom()
 * val seam = loom.host(pattern)              // state == Weaving
 * // ... seam is Weaving here ...
 * (seam as DelayedWovenSeam).markWoven()     // state → Woven
 * ```
 *
 * Frame routing is in-memory. [broadcast] and [sendTo] deliver frames to all
 * other seams on the same loom regardless of [SeamState] — this lets tests
 * inspect whether frames sent while [SeamState.Weaving] ever arrive.
 */
public class DelayedWovenLoom : Loom {
    private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())
    private val links = mutableMapOf<PeerId, DelayedWovenSeam>()
    private var counter = 0

    override fun availability(): FabricAvailability = FabricAvailability.Available

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val id = freshId()
        val seam = DelayedWovenSeam(id, this)
        links[id] = seam
        _peers.update { it + id }
        return seam
    }

    internal fun dispatch(sender: PeerId, payload: ByteArray, recipient: PeerId?) {
        val targets = if (recipient == null) {
            links.keys.filter { it != sender }
        } else {
            listOf(recipient)
        }
        for (targetId in targets) {
            val target = links[targetId] ?: continue
            val swatch = Swatch(payload = payload, sender = sender)
            target.deliver(swatch)
        }
    }

    internal fun remove(id: PeerId) {
        links.remove(id)
        _peers.update { it - id }
    }

    internal val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private fun freshId(): PeerId {
        counter++
        return PeerId("delayed-woven-$counter")
    }
}

/**
 * A [Seam] produced by [DelayedWovenLoom] that starts [SeamState.Weaving].
 *
 * Call [markWoven] to transition to [SeamState.Woven], simulating a radio
 * fabric completing its link-establishment phase.
 */
public class DelayedWovenSeam internal constructor(
    override val selfId: PeerId,
    private val loom: DelayedWovenLoom,
) : Seam {
    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    override val peers: StateFlow<Set<PeerId>> = loom.peers

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    private var sequenceCounter = 0L
    private var closed = false

    /** Transition this seam from [SeamState.Weaving] to [SeamState.Woven]. */
    public fun markWoven() {
        _state.compareAndSet(SeamState.Weaving, SeamState.Woven)
    }

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        loom.dispatch(sender = selfId, payload = payload, recipient = null)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotClosed()
        if (peer !in loom.peers.value) throw PeerNotConnected(peer)
        loom.dispatch(sender = selfId, payload = payload, recipient = peer)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _state.value = SeamState.Torn(reason)
        loom.remove(selfId)
        incomingChannel.close()
    }

    internal fun deliver(swatch: Swatch) {
        if (!closed) {
            incomingChannel.trySend(
                swatch.copy(sequence = ++sequenceCounter),
            )
        }
    }

    private fun checkNotClosed() {
        check(_state.value !is SeamState.Torn) { "DelayedWovenSeam for $selfId is closed" }
    }
}
