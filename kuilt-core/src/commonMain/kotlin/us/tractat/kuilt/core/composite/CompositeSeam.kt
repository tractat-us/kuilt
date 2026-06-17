package us.tractat.kuilt.core.composite

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
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
 * over every live, non-torn ply. [sendTo] resolves the composite id to every
 * reachable `(ply, transportId)` in send-preference order and tries them in turn,
 * falling through to the next when a ply tears mid-send.
 *
 * **Receive:** Inbound [PlyFrame.Data] frames are de-duplicated and reordered per
 * origin by a [PlyInboundGate]; application payloads emerge as [Swatch] values.
 *
 * **Thread-safety.** This type is correct under a *multi-threaded* dispatcher — the
 * injected [dispatcher] is only the scope for the internal coroutines (the reconcile,
 * rollup, announce, per-ply inbound and peers pumps); it is **not** a mutual-exclusion
 * mechanism. The mutable state shared between caller threads (`broadcast`/`sendTo`) and
 * those pumps — the live-ply map, the learned `(plyId, transportId) → compositeId`
 * mapping, and the per-origin [PlyInboundGate] (itself documented single-collection) — is
 * guarded by a single [reentrantLock]. The outbound sequence is an atomic counter and
 * teardown is gated by an atomic single-shot flag. Suspending ply calls
 * (`Seam.broadcast`/`sendTo`/`close`, `cancelAndJoin`) are NEVER invoked while the lock is
 * held: callers snapshot the target plies under the lock, release, then send/close outside it.
 *
 * @param dispatcher The scope for the seam's internal coroutines (scheduling only — see the
 *   thread-safety note above). Production callers pass `Dispatchers.Default`; test callers
 *   pass a dispatcher derived from the test scheduler so the seam's pumps share the same
 *   virtual clock as the test, driving reconciliation eagerly.
 */
internal class CompositeSeam(
    initial: List<Pair<PlyId, Seam>>,
    private val rendezvous: Rendezvous,
    private val desired: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = PlyInboundGate()

    // Outbound envelope sequence. Stamped by concurrent broadcast/sendTo callers.
    private val outSeq = atomic(0L)

    // Single lock guarding `live`, `idMap`, and the inbound `gate`. Every read and mutation
    // of those happens under it; suspending ply calls are always done OUTSIDE it (snapshot
    // under the lock, act after releasing).
    private val lock = reentrantLock()

    // Atomic single-shot teardown gate so `close()` runs the seam-wide teardown exactly once.
    private val closed = atomic(false)

    // Minted once from the initial set; never recomputed, so it survives ply churn.
    override val selfId: PeerId = mintCompositeId(initial)

    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val _plies = MutableStateFlow<Map<PlyId, SeamState>>(emptyMap())
    override val plies: StateFlow<Map<PlyId, SeamState>> = _plies.asStateFlow()

    // (plyId, transport id) -> composite id; built as Announce frames arrive. Guarded by [lock].
    private val idMap = mutableMapOf<Pair<PlyId, PeerId>, PeerId>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    // PlyId -> live ply, in send-preference (insertion) order. A LinkedHashMap so
    // broadcast/sendTo iterate most-preferred-first. Guarded by [lock].
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
        val liveIds = lock.withLock { live.keys.toList() }
        liveIds.forEach { id -> if (id !in desiredIds) detachPly(id) }
        // Attach: desired plies not yet live — weave their loom now.
        for ((id, loom) in desiredSet) {
            val alreadyLive = lock.withLock { id in live }
            if (!alreadyLive) attachPly(id, loom.weave(rendezvous))
        }
    }

    private fun attachPly(id: PlyId, seam: Seam) {
        // Per-ply pumps run under a child Job so detach cancels exactly this ply.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val plyScope = CoroutineScope(scope.coroutineContext + job)

        seam.state
            .onEach { s -> _plies.update { it + (id to s) } }
            .launchIn(plyScope)

        // Re-announce on every Woven transition (cold start + recovery). Best-effort: the
        // ply may tear between this Woven emission and the send (the Seam contract throws
        // IllegalStateException on a Torn send), and the far side re-learns the mapping on
        // the next Woven/peers event regardless — so swallow a failed announce (#535).
        seam.state
            .onEach {
                if (it is SeamState.Woven) {
                    runCatchingCancellable { seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                }
            }
            .launchIn(plyScope)

        seam.incoming
            .onEach { swatch -> onPlyFrame(id, swatch) }
            .launchIn(plyScope)

        // Recompute peers on transport membership changes; re-announce to newcomers.
        seam.peers
            .onEach { newPeers ->
                recomputePeers()
                if (newPeers.size > 1 && seam.state.value is SeamState.Woven) {
                    // Best-effort re-announce to newcomers — swallow a torn-ply send (#535).
                    runCatchingCancellable { seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                }
            }
            .launchIn(plyScope)

        lock.withLock { live[id] = PlyHandle(seam, job) }
    }

    private suspend fun detachPly(id: PlyId) {
        // Remove from the live map under the lock; the suspending teardown runs outside it.
        val handle = lock.withLock { live.remove(id) } ?: return
        // Stop this ply's pumps FIRST so a resuming pump can't resurrect the
        // _plies/idMap entries we are about to purge.
        handle.job.cancelAndJoin()
        // Remove from the per-ply map (now safe) so the aggregate rolls up
        // without this ply — empty => Weaving, never a transient terminal Torn.
        _plies.update { it - id }
        // Purge this ply's learned mappings so a re-attach starts clean.
        lock.withLock { idMap.keys.removeAll { it.first == id } }
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
                lock.withLock { idMap[plyId to sender] = frame.compositeId }
                recomputePeers()
            }
            is PlyFrame.Data -> {
                // Data uses the in-frame originId — the transport sender may be a gateway.
                // The gate is single-collection by contract; the lock restores that invariant
                // across the concurrent per-ply inbound pumps. trySend is channel-safe outside it.
                val payloads = lock.withLock { gate.accept(frame) }
                payloads.forEach { payload ->
                    incomingChannel.trySend(Swatch(payload = payload, sender = frame.originId))
                }
            }
        }
    }

    private fun recomputePeers() {
        val reachable = lock.withLock {
            buildSet {
                add(selfId)
                idMap.forEach { (key, compositeId) ->
                    val (plyId, transportId) = key
                    val seam = live[plyId]?.seam
                    if (seam != null && transportId in seam.peers.value) add(compositeId)
                }
            }
        }
        _peers.value = reachable
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq.getAndIncrement(), payload))
        // Snapshot the live, non-torn plies under the lock, then send OUTSIDE it.
        val targets = lock.withLock { live.values.toList() }
        targets
            .filter { it.seam.state.value !is SeamState.Torn }
            // Best-effort per ply: a ply can tear between the filter and the send (the Seam
            // contract throws on a Torn send), and the point of bonding plies is that one
            // tearing must not fail a broadcast another ply can carry (#542).
            .forEach { runCatchingCancellable { it.seam.broadcast(bytes) } }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq.getAndIncrement(), payload))
        // Resolve every (ply, transportId) that can reach `peer`, in send-preference order under the
        // lock; send OUTSIDE it. A candidate can tear between the resolve and the send (the Seam
        // contract throws on a Torn send) — the point of bonding plies is that one ply tearing must
        // not fail a sendTo another ply can carry, so fall through to the next candidate (#542).
        val candidates = lock.withLock { resolveSendTargets(peer) }
        for ((handle, transportId) in candidates) {
            val sent = runCatchingCancellable {
                handle.seam.sendTo(transportId, bytes)
                true
            }.getOrDefault(false)
            if (sent) return
        }
        throw PeerNotConnected(peer)
    }

    /** Every live, non-torn ply that can reach [peer], in send-preference order. Call under [lock]. */
    private fun resolveSendTargets(peer: PeerId): List<Pair<PlyHandle, PeerId>> =
        buildList {
            for ((plyId, handle) in live) {
                if (handle.seam.state.value is SeamState.Torn) continue
                val transportId = idMap.entries
                    .firstOrNull { (k, v) -> k.first == plyId && v == peer }
                    ?.key?.second
                if (transportId != null && transportId in handle.seam.peers.value) {
                    add(handle to transportId)
                }
            }
        }

    override suspend fun close(reason: CloseReason) {
        // Single-shot: only the first caller publishes Torn and tears down.
        if (!closed.compareAndSet(expect = false, update = true)) return
        scope.cancel()
        // Snapshot the plies to close under the lock; perform the suspending closes outside it.
        val toClose = lock.withLock {
            val snapshot = live.values.toList()
            live.clear()
            snapshot
        }
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        toClose.forEach { it.seam.close(reason) }
    }

    private companion object {
        fun mintCompositeId(initial: List<Pair<PlyId, Seam>>): PeerId =
            PeerId("composite-" + initial.joinToString("-") { it.second.selfId.value })
    }
}
