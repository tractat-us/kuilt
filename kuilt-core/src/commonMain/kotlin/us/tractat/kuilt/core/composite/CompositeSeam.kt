package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.coroutines.CoroutineContext

/**
 * The composite `Seam` woven by [CompositeLoom]. Presents a single peer set,
 * `incoming` flow, and send surface over a set of constituent plies that may
 * change while the session is live.
 *
 * **Dynamic plies:** [initial] is the set woven by [CompositeLoom] before
 * `weave()` returns. Thereafter the composite collects [desired] and reconciles:
 * a [PlyId] that appears is woven and attached; one that disappears is closed and
 * detached. The static (fixed-list) case is the degenerate one where [desired]
 * never changes after its first value.
 *
 * **Identity:** Each peer mints a composite [selfId] once from [initial] and never
 * recomputes it, so it is stable across attach/detach. On each ply reaching
 * [SeamState.Woven] the peer broadcasts a [PlyFrame.Announce] so the far side can
 * map `(plyId, transportId) → compositeId`.
 *
 * **Send:** [broadcast] wraps the payload in a [PlyFrame.Data] envelope and sends
 * over every live, non-torn ply. [sendTo] resolves the composite id to a
 * `(ply, transportId)` in send-preference order. Both run on [dispatcher] so they
 * never race reconcile's mutation of the live set.
 *
 * **Receive:** Inbound [PlyFrame.Data] frames are de-duplicated and reordered per
 * origin by a [PlyInboundGate]; application payloads emerge as [Swatch] values.
 *
 * @param dispatcher Confines all internal state access (reconcile, rollup,
 *   announce, inbound pumps, send) to a single thread. Production uses the confined
 *   default ([Dispatchers.Default.limitedParallelism(1)]); tests inject
 *   [UnconfinedTestDispatcher] to drive reconciliation eagerly.
 */
internal class CompositeSeam(
    initial: List<Pair<PlyId, Seam>>,
    private val rendezvous: Rendezvous,
    private val desired: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = PlyInboundGate()
    private var outSeq = 0L

    // Minted once from the initial set; never recomputed, so it survives ply churn.
    override val selfId: PeerId = mintCompositeId(initial)

    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val _plies = MutableStateFlow<Map<PlyId, SeamState>>(emptyMap())
    override val plies: StateFlow<Map<PlyId, SeamState>> = _plies.asStateFlow()

    // (plyId, transport id) -> composite id; built as Announce frames arrive.
    private val idMap = mutableMapOf<Pair<PlyId, PeerId>, PeerId>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    // PlyId -> live ply, in send-preference (insertion) order. A LinkedHashMap so
    // broadcast/sendTo iterate most-preferred-first. Mutated only on [dispatcher].
    private val live = LinkedHashMap<PlyId, PlyHandle>()

    private class PlyHandle(val seam: Seam, val job: Job)

    init {
        // Aggregate state is derived from the per-ply map. Empty => Weaving.
        _plies
            .onEach { _state.value = rollup(it.values.toList()) }
            .launchIn(scope)

        // Seed the initial plies (already woven by CompositeLoom).
        initial.forEach { (id, seam) -> attachPly(id, seam) }

        // Reconcile on every desired-set change. The first emission equals the
        // initial set, so it produces no attach/detach.
        desired
            .onEach { reconcile(it) }
            .launchIn(scope)
    }

    private suspend fun reconcile(desiredSet: List<Pair<PlyId, Loom>>) {
        val desiredIds = desiredSet.map { it.first }.toSet()
        // Detach: live plies no longer desired.
        live.keys.toList().forEach { id -> if (id !in desiredIds) detachPly(id) }
        // Attach: desired plies not yet live — weave their loom now.
        for ((id, loom) in desiredSet) {
            if (id !in live) attachPly(id, loom.weave(rendezvous))
        }
    }

    private fun attachPly(id: PlyId, seam: Seam) {
        // Per-ply pumps run under a child Job so detach cancels exactly this ply.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val plyScope = CoroutineScope(scope.coroutineContext + job)

        seam.state
            .onEach { s -> _plies.value = _plies.value + (id to s) }
            .launchIn(plyScope)

        // Re-announce on every Woven transition (cold start + recovery).
        seam.state
            .onEach { if (it is SeamState.Woven) seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
            .launchIn(plyScope)

        seam.incoming
            .onEach { swatch -> onPlyFrame(id, swatch) }
            .launchIn(plyScope)

        // Recompute peers on transport membership changes; re-announce to newcomers.
        seam.peers
            .onEach { newPeers ->
                recomputePeers()
                if (newPeers.size > 1 && seam.state.value is SeamState.Woven) {
                    seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId)))
                }
            }
            .launchIn(plyScope)

        live[id] = PlyHandle(seam, job)
    }

    private suspend fun detachPly(id: PlyId) {
        val handle = live.remove(id) ?: return
        // Remove from the per-ply map BEFORE closing so the aggregate never
        // transiently latches Torn (terminal) on the last ply.
        _plies.value = _plies.value - id
        // Purge this ply's learned mappings so a re-attach starts clean.
        idMap.keys.removeAll { it.first == id }
        handle.job.cancel()
        handle.seam.close(CloseReason.Normal)
        recomputePeers()
    }

    private fun rollup(states: List<SeamState>): SeamState =
        when {
            states.isEmpty() -> SeamState.Weaving
            states.any { it is SeamState.Woven } -> SeamState.Woven
            states.all { it is SeamState.Torn } -> states.filterIsInstance<SeamState.Torn>().first()
            else -> SeamState.Weaving
        }

    private fun onPlyFrame(plyId: PlyId, swatch: Swatch) {
        when (val frame = PlyFrame.decode(swatch.payload)) {
            is PlyFrame.Announce -> {
                // Announce keys idMap by (plyId, transport sender) → composite id.
                val sender = swatch.sender ?: return
                idMap[plyId to sender] = frame.compositeId
                recomputePeers()
            }
            is PlyFrame.Data -> {
                // Data uses the in-frame originId — the transport sender may be a gateway.
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
                val (plyId, transportId) = key
                val seam = live[plyId]?.seam
                if (seam != null && transportId in seam.peers.value) add(compositeId)
            }
        }
        _peers.value = reachable
    }

    override suspend fun broadcast(payload: ByteArray) = withContext(dispatcher) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        live.values
            .filter { it.seam.state.value !is SeamState.Torn }
            .forEach { it.seam.broadcast(bytes) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = withContext(dispatcher) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        for ((plyId, handle) in live) {
            if (handle.seam.state.value is SeamState.Torn) continue
            val transportId = idMap.entries
                .firstOrNull { (k, v) -> k.first == plyId && v == peer }
                ?.key?.second
            if (transportId != null && transportId in handle.seam.peers.value) {
                handle.seam.sendTo(transportId, bytes)
                return@withContext
            }
        }
        throw PeerNotConnected(peer)
    }

    override suspend fun close(reason: CloseReason) {
        live.values.forEach { it.seam.close(reason) }
        live.clear()
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        scope.cancel()
    }

    private companion object {
        fun mintCompositeId(initial: List<Pair<PlyId, Seam>>): PeerId =
            PeerId("composite-" + initial.joinToString("-") { it.second.selfId.value })
    }
}
