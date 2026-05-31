package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

/**
 * The composite `Seam` woven by [CompositeLoom]. Presents a single peer set,
 * `incoming` flow, and send surface over N constituent plies.
 *
 * **Identity:** Each peer mints a composite [selfId] (distinct from any per-ply
 * transport id). On each ply reaching [SeamState.Woven], the peer broadcasts an
 * [PlyFrame.Announce] so the far side can map `(ply, transportId) → compositeId`.
 *
 * **Send:** [broadcast] wraps the payload in a [PlyFrame.Data] envelope and sends
 * it over every live ply. [sendTo] resolves the composite id to a `(ply, transportId)`
 * in list order (most-preferred first).
 *
 * **Receive:** Inbound [PlyFrame.Data] frames are de-duplicated and reordered per
 * origin by a [PlyInboundGate]; application payloads emerge as [Swatch] values.
 */
internal class CompositeSeam(
    private val constituents: List<Pair<PlyId, Seam>>,
) : Seam {
    // Confined to one thread: idMap / _plies / outSeq / gate are all non-thread-safe and must
    // not be accessed concurrently. limitedParallelism(1) serialises all dispatches.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val gate = PlyInboundGate()
    private var outSeq = 0L

    // A fresh composite identity — distinct from any per-ply transport id.
    override val selfId: PeerId = mintCompositeId(constituents)

    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val _plies = MutableStateFlow(constituents.associate { (id, seam) -> id to seam.state.value })
    override val plies: StateFlow<Map<PlyId, SeamState>> = _plies.asStateFlow()

    // (ply index, transport id) -> composite id; built as Announce frames arrive.
    private val idMap = mutableMapOf<Pair<Int, PeerId>, PeerId>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    init {
        // Roll each ply's state up: any Woven => Woven; all Torn => Torn(first reason); else Weaving.
        combine(constituents.map { it.second.state }) { states -> rollup(states.toList()) }
            .onEach { _state.value = it }
            .launchIn(scope)

        // Track per-ply breakdown.
        constituents.forEach { (id, seam) ->
            seam.state
                .onEach { s -> _plies.value = _plies.value + (id to s) }
                .launchIn(scope)
        }
    }

    init {
        constituents.forEachIndexed { index, (_, seam) ->
            // Re-send Announce on every Woven transition so a ply that drops and recovers
            // re-learns its peers.
            seam.state
                .onEach { if (it is SeamState.Woven) seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                .launchIn(scope)

            // Inbound pump: decode frames, reconcile announcements, deliver data.
            seam.incoming
                .onEach { swatch -> onPlyFrame(index, swatch) }
                .launchIn(scope)

            // Recompute composite peers when a ply's transport-level membership changes.
            seam.peers
                .onEach { recomputePeers() }
                .launchIn(scope)
        }
    }

    private fun rollup(states: List<SeamState>): SeamState =
        when {
            states.any { it is SeamState.Woven } -> SeamState.Woven
            states.all { it is SeamState.Torn } -> states.filterIsInstance<SeamState.Torn>().first()
            else -> SeamState.Weaving
        }

    private fun onPlyFrame(plyIndex: Int, swatch: Swatch) {
        when (val frame = PlyFrame.decode(swatch.payload)) {
            is PlyFrame.Announce -> {
                // Announce keys idMap by (plyIndex, transport sender) → composite id.
                // Requires a non-null transport sender to establish the mapping.
                val sender = swatch.sender ?: return
                idMap[plyIndex to sender] = frame.compositeId
                recomputePeers()
            }
            is PlyFrame.Data -> {
                // Data uses the in-frame originId — the transport sender is irrelevant
                // (a gateway-forwarded frame's transport sender is the gateway, not the origin).
                gate.accept(frame).forEach { payload ->
                    incomingChannel.trySend(Swatch(payload = payload, sender = frame.originId))
                }
            }
        }
    }

    private fun recomputePeers() {
        val reachable = buildSet {
            add(selfId)
            idMap.forEach { (key, compositeId) ->
                val (plyIndex, transportId) = key
                if (transportId in constituents[plyIndex].second.peers.value) add(compositeId)
            }
        }
        _peers.value = reachable
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        constituents.forEach { (_, seam) -> seam.broadcast(bytes) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        for (index in constituents.indices) {
            val transportId = idMap.entries
                .firstOrNull { (k, v) -> k.first == index && v == peer }
                ?.key?.second
            if (transportId != null && transportId in constituents[index].second.peers.value) {
                constituents[index].second.sendTo(transportId, bytes)
                return
            }
        }
        throw PeerNotConnected(peer)
    }

    override suspend fun close(reason: CloseReason) {
        constituents.forEach { (_, seam) -> seam.close(reason) }
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        scope.cancel()
    }

    private companion object {
        fun mintCompositeId(constituents: List<Pair<PlyId, Seam>>): PeerId =
            PeerId("composite-" + constituents.joinToString("-") { it.second.selfId.value })
    }
}
