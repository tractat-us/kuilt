package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable

private val log = KotlinLogging.logger("us.tractat.kuilt.cluster.ManagedSeam")

/**
 * A [Seam] whose backing seam can be replaced on transport tear without recreating
 * the [us.tractat.kuilt.raft.RaftNode] that rides on top of it.
 *
 * The client's [RaftNode] is built once — over a stable [RoutedRaftTransport] whose
 * relay channel is this [ManagedSeam]. When the entry server dies and the client
 * re-joins another, only the backing [Seam] is [swap]ped; the node keeps its
 * identity, log, and its single collector of [incoming]. This is the primitive that
 * makes [us.tractat.kuilt.cluster.clusterClient]'s cross-server failover possible.
 *
 * ## Lifecycle
 *
 * 1. Construct with the client's stable [selfId]. Before the first [swap] there is
 *    no backing seam — [peers] is `{ selfId }` and [broadcast]/[sendTo] drop
 *    frames (debug-logged, never thrown).
 * 2. Build the [RoutedRaftTransport]/[RaftNode] over this seam.
 * 3. Call [swap] with the first (and each subsequent) joined [Seam].
 *
 * ## Single collection across swaps
 *
 * [incoming] is a hot [MutableSharedFlow] that is **stable across swaps** — the
 * transport collects it exactly once for the whole client lifetime. Each [swap]
 * cancels the previous per-swap relay coroutine **first** (so at most one collector
 * of any backing seam is ever live) and starts a fresh one that pumps the new
 * seam's `incoming` into [incoming] and tracks its `peers`. This deliberately does
 * **not** propagate a backing seam's [SeamState.Torn] to [incoming]: the whole
 * point is that the node survives the tear.
 *
 * ## The published roster is re-attributed, not copied
 *
 * [peers] is the backing seam's **remotes** under **this** seam's identity — its own id swapped in
 * for the backing seam's. Copying the backing roster through would breach both halves of the
 * [Seam.peers] contract at once: it drops [selfId], which that property says the set always
 * contains, and it publishes the backing seam's id, which nothing here can address. See
 * `reattributed` for the full argument (#2436).
 *
 * ## Thread safety
 *
 * The current backing seam pointer is guarded by an atomicfu reentrant lock; the
 * lock is never held across a suspend call. [peers] and [incoming] are safe
 * concurrent flows. Correct under a multi-threaded dispatcher.
 *
 * @param scope parents the per-swap relay coroutines; must outlive all [swap] calls.
 *   **Required** — no real-dispatcher default.
 * @param selfId this client's stable [PeerId] — does not change across reconnects.
 */
internal class ManagedSeam(
    private val scope: CoroutineScope,
    override val selfId: PeerId,
) : Seam {

    private val lock = reentrantLock()

    // Null until the first swap. Sends before that are dropped (debug-logged).
    private var current: Seam? = null

    private val _peers: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // This logical seam never tears — it outlives individual backing seams, which is
    // the reason it exists. Constant Woven.
    // ALLOW-bareSeamState: a constant — nothing retains a handle to the flow, so it has no writer at all.
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)

    private val _incoming: MutableSharedFlow<Swatch> =
        MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)
    override val incoming: Flow<Swatch> = _incoming

    // The active relay job — one per backing seam. Cancelled on each swap.
    private val relayJob = atomic<Job?>(null)

    override suspend fun broadcast(payload: ByteArray) {
        val seam = lock.withLock { current }
        if (seam == null) {
            log.debug { "managed-seam: $selfId broadcast dropped — no backing seam yet" }
            return
        }
        runCatchingCancellable { seam.broadcast(payload) }
            .onFailure { log.debug { "managed-seam: $selfId broadcast dropped on tear" } }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        // Its OWN [selfId] — a constructor parameter, not a read of the backing seam — and ahead of
        // everything else, for two independent reasons (#2428). The published identity is this one,
        // so it is the id a caller can self-send to; and the delegation below swallows the backing
        // seam's exceptions into a debug log, which would launder the refusal into a silent drop.
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        val seam = lock.withLock { current }
        if (seam == null) {
            log.debug { "managed-seam: $selfId sendTo $peer dropped — no backing seam yet" }
            return
        }
        runCatchingCancellable { seam.sendTo(peer, payload) }
            .onFailure { log.debug { "managed-seam: $selfId sendTo $peer dropped on tear" } }
    }

    /**
     * Replace the backing seam with [newSeam].
     *
     * Cancels the current relay coroutine **first** (preserving single-collection —
     * no two collectors of a backing seam ever overlap), installs [newSeam],
     * refreshes [peers] from it — its remotes, under this seam's identity — and starts a fresh
     * relay coroutine. The transport observing [incoming]/[peers] continues without interruption.
     *
     * Must not be called concurrently with another [swap].
     */
    fun swap(newSeam: Seam) {
        // Cancel the old relay FIRST so the old backing seam has no live collector
        // once the new one is installed.
        relayJob.value?.cancel()
        lock.withLock {
            current = newSeam
            _peers.value = reattributed(newSeam.peers.value, newSeam.selfId)
        }
        startRelay(newSeam)
    }

    /**
     * The roster this seam publishes for a backing seam: that seam's **remotes**, under **this**
     * seam's identity.
     *
     * The two edits are one idea seen from either end of the swap. Adding [selfId] is
     * [Seam.peers]' invariant — *"Live set of peers currently connected. Includes `selfId`"* — and
     * this seam's id is a constructor parameter, so a backing roster naming the fabric's own choice
     * of id does not contain it. Dropping [backingSelfId] is the same property's addressability
     * pairing: *"A peer in `peers` must be addressable by [sendTo] … an implementation that
     * publishes a peer it has no route to is the bug, not the caller that believed it."* Nothing can
     * address the backing seam's own id through here — [sendTo] would hand it to that seam, which
     * refuses its own id, and the refusal is swallowed into a debug line.
     *
     * The two ids coincide in the intended `clusterClient` wiring — the loom's `selfPeerId` is
     * pinned to `clientNodeId` so the wire identity survives a failover — and there this is exactly
     * the old copy-through. Where they differ it is the difference between a roster with one
     * upstream server in it and one `RoutedRaftTransport.playerServerHop` reads as a mis-wired
     * multi-peer relay channel, dropping every relayed send.
     */
    private fun reattributed(roster: Set<PeerId>, backingSelfId: PeerId): Set<PeerId> =
        roster - backingSelfId + selfId

    private fun startRelay(seam: Seam) {
        val job = scope.launch {
            // Per-swap peer tracker: a StateFlow never completes, so it lives as a
            // child of the relay job and is cancelled by the next swap / teardown.
            // Re-attributed on every emission, not only at swap — the backing roster
            // moves as peers come and go, and each move is a fresh chance to drop selfId.
            launch { seam.peers.collect { _peers.value = reattributed(it, seam.selfId) } }
            runCatchingCancellable {
                seam.incoming.collect { _incoming.emit(it) }
            }.onFailure { log.debug { "managed-seam: $selfId relay ended: ${it.message}" } }
        }
        relayJob.value = job
    }

    /**
     * Stop relaying the current backing seam and close it. Idempotent. The logical
     * [ManagedSeam] itself does not tear — cancelling [scope] is what ends it.
     *
     * [peers] collapses back to `{ selfId }`, the same roster the constructor publishes before the
     * first [swap] and for the same reason: with no backing seam there is no route to anyone, and
     * every send is dropped. Leaving the closed seam's remotes behind would advertise peers
     * [sendTo] silently discards.
     */
    override suspend fun close(reason: CloseReason) {
        val seam = lock.withLock {
            val s = current
            current = null
            s
        }
        // Cancel the tracker BEFORE collapsing, so no in-flight emission of the outgoing seam's
        // roster can be published over the collapse.
        relayJob.value?.cancel()
        _peers.value = setOf(selfId)
        runCatchingCancellable { seam?.close(reason) }
    }
}
