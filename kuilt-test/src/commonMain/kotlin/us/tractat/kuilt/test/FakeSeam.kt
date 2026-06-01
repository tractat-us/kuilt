package us.tractat.kuilt.test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

/**
 * A test double for [Seam] with test-driver helpers for state mutation, frame
 * delivery, and outgoing-frame inspection.
 *
 * Defaults make `FakeSeam()` a ready-to-use, single-peer, [SeamState.Woven] seam
 * in one line:
 *
 * ```kotlin
 * val seam = FakeSeam()
 * seam.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
 * val frame = seam.incoming.first()
 * ```
 *
 * For wired two-peer scenarios, prefer [fakeSeamPair] which cross-wires broadcast
 * delivery between two seams.
 *
 * **Send semantics** (matching the [Seam] contract):
 * - [broadcast] while [SeamState.Weaving] or [SeamState.Woven] with no other peers:
 *   no-op, but the payload is still recorded in [broadcasts].
 * - [sendTo] to a peer not in [peers]: throws [PeerNotConnected].
 * - Either send while [SeamState.Torn]: throws [IllegalStateException].
 */
public class FakeSeam(
    override val selfId: PeerId = PeerId("self"),
    initialPeers: Set<PeerId> = setOf(PeerId("self")),
    initialState: SeamState = SeamState.Woven,
) : Seam {
    private val _peers = MutableStateFlow(initialPeers)
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    private val _broadcasts = mutableListOf<ByteArray>()
    private val _directed = mutableListOf<Pair<PeerId, ByteArray>>()
    private var sequenceCounter = 0L

    /** All payloads passed to [broadcast], in call order. */
    public val broadcasts: List<ByteArray> get() = _broadcasts.toList()

    /** All (peer, payload) pairs passed to [sendTo], in call order. */
    public val directed: List<Pair<PeerId, ByteArray>> get() = _directed.toList()

    /**
     * Optional hook invoked after [broadcast] is recorded (and state/peers checks pass).
     * Used internally by [fakeSeamPair] to cross-wire delivery. Not part of the public API.
     */
    internal var onBroadcast: (suspend (ByteArray) -> Unit)? = null

    // ── Seam interface ────────────────────────────────────────────────────────

    override suspend fun broadcast(payload: ByteArray) {
        checkNotTorn()
        _broadcasts.add(payload)
        onBroadcast?.invoke(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotTorn()
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        _directed.add(peer to payload)
    }

    override suspend fun close(reason: CloseReason) {
        if (_state.value is SeamState.Torn) return
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
    }

    // ── Test-driver helpers ───────────────────────────────────────────────────

    /** Add [peer] to the live peers set. */
    public fun addPeer(peer: PeerId) {
        _peers.update { it + peer }
    }

    /** Remove [peer] from the live peers set. */
    public fun removePeer(peer: PeerId) {
        _peers.update { it - peer }
    }

    /** Transition state from [SeamState.Weaving] to [SeamState.Woven]. */
    public fun weave() {
        _state.value = SeamState.Woven
    }

    /** Transition state to [SeamState.Torn] with [reason]. */
    public fun tear(reason: CloseReason = CloseReason.Normal) {
        _state.value = SeamState.Torn(reason)
    }

    /** Push [swatch] directly into [incoming]. */
    public suspend fun deliver(swatch: Swatch) {
        incomingChannel.send(swatch)
    }

    /**
     * Push a frame from [from] into [incoming], stamping [sender] and a
     * monotonically increasing [sequence] (receiver-local, starting at 1).
     */
    public suspend fun deliver(from: PeerId, payload: ByteArray) {
        deliver(Swatch(payload = payload, sender = from, sequence = ++sequenceCounter))
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    internal fun nextSequence(): Long = ++sequenceCounter

    private fun checkNotTorn() {
        check(_state.value !is SeamState.Torn) { "Seam for $selfId is torn" }
    }
}
