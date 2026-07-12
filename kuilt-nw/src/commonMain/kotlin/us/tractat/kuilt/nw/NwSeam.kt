package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.random.Random

private val log = KotlinLogging.logger("us.tractat.kuilt.nw.NwSeam")

/**
 * Full-mesh [Seam] over an [NwApi] — one peer's symmetric view of an N-peer
 * Network.framework session.
 *
 * ## Architecture B — one demux loop, identity resolved inline
 * [NwApi.bytesReceived] is a SINGLE flow multiplexing every connection. `NwSeam`
 * collects it **exactly once** (one receive loop) and demuxes by [NwConnectionId] —
 * there is no per-connection machine/collector. The seam owns the connection lifecycle
 * from [NwApi.connectionOpened] onward; discovery + dialling belong to `NwLoom` (Task 2.7).
 *
 * Three collectors, all launched [CoroutineStart.UNDISPATCHED] at construction so they
 * subscribe **before** `NwLoom` triggers advertise/browse/dial (subscribe-before-trigger,
 * since [NwApi]'s flows are hot with no replay):
 *
 *  1. **connectionOpened** — sends our identity frame ([NwHello]: this peer's [PeerId] plus this
 *     connection's per-connection dedup nonce).
 *  2. **bytesReceived** — the demux + inline handshake: the first decoded frame on an unresolved
 *     connection is the remote's [NwHello] (id + nonce); every later frame is data, delivered to
 *     [incoming] stamped with that sender.
 *  3. **connectionClosed** — evicts the peer (conn-identity guarded so a deduped loser's close
 *     can't evict the survivor) and tears the seam when the last remote drops.
 *
 * ## Duplicate-dial dedup (canonical-nonce rule, direction-free)
 * A full mesh double-dials each pair, producing two connections to the same peer. Each [ConnState]
 * mints a random [nonce][ConnState.nonce] **once, when it is created** (in the `getOrPut` factory —
 * so the nonce is available no matter which of the two loops observes the connection first). Each
 * end sends its nonce in its [NwHello]. When a connection to `remoteId` resolves and another to the
 * same `remoteId` already exists, the survivor is the one with the **smaller canonical link nonce**
 * — `canonicalLinkNonce(myNonce, remoteNonce)`, an order-independent function of the two nonces
 * (sort their hex, join `"lo:hi"`). Because both ends see the same two nonces, both compute the same
 * canonical value and pick the same survivor with **no coordination and no dependence on dial
 * direction or collector ordering**; the loser is disconnected and its later close is a no-op
 * (conn-identity guard). This is a port of `:kuilt-core`'s `MeshSeam` rule — the old direction-based
 * rule could wedge a pair to zero under a multi-threaded dispatcher (direction was written by one
 * collector and read by another with no happens-before); the nonce rule cannot.
 *
 * ## Thread-safety
 * The [registry] and [conns] maps are shared across the three collectors (each `collect` is
 * internally sequential, but they run concurrently) and the caller-driven [broadcast]/[sendTo].
 * All map access is guarded by one [reentrantLock] (atomicfu). **No `suspend`/`api.*` call ever
 * runs under the lock** — targets are snapshotted under the lock, then sent/disconnected/delivered
 * outside it. Correct under a multi-threaded dispatcher; no single-thread-confinement crutch.
 *
 * @param selfId this peer's stable identity, sent (with a per-connection nonce) as the first framed
 *   message on each connection.
 * @param api    the transport moving raw bytes over open connections.
 * @param scope  coroutine scope hosting the three collectors; cancelled on teardown.
 * @param random source of per-connection dedup nonces; production defaults to [Random.Default], tests
 *   inject a seeded [Random] so the dedup tiebreak is deterministic.
 * @param policy delivery policy for the inbound [Spool] (default [DeliveryPolicy.Reliable]).
 */
internal class NwSeam(
    override val selfId: PeerId,
    private val api: NwApi,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Seam {

    /**
     * Per-connection mutable state. All fields read/written only under [lock]; [framer] is driven
     * only by the single bytes loop. [nonce] is minted once at creation and never mutated — it is
     * this connection's contribution to the canonical dedup nonce.
     */
    private class ConnState(val nonce: ByteArray) {
        val framer: NwFramer = NwFramer()
        var resolvedPeerId: PeerId? = null
    }

    /** The live connection carrying a resolved peer, plus the canonical nonce both ends agreed on. */
    private data class Winner(val connId: NwConnectionId, val canonicalNonce: String)

    private val lock = reentrantLock()

    /** Resolved remote identity → the live connection carrying it (+ its canonical link nonce). */
    private val registry = mutableMapOf<PeerId, Winner>()

    /** Every connection this seam has seen → its [ConnState]. */
    private val conns = mutableMapOf<NwConnectionId, ConnState>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Weaving until the first remote peer resolves, then Woven; latched Torn on teardown.
    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Single latch flag, read/written across every path (receive/close/send).
    private val closed = atomic(false)

    // Stamped onto every delivered Swatch; incremented from the single bytes loop but atomic for safety.
    private val seq = atomic(0L)

    private val closedMessage get() = "NwSeam for ${selfId.value} is closed"

    // UNDISPATCHED so all three collectors subscribe synchronously at construction — before any
    // connectionOpened/bytes/close event can be emitted (subscribe-before-trigger).
    private val openedJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionOpenedLoop() }
    private val bytesJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bytesReceivedLoop() }
    private val closedJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionClosedLoop() }

    // ── loop 1: connectionOpened ────────────────────────────────────────────────

    private suspend fun connectionOpenedLoop() {
        api.connectionOpened.collect { event ->
            if (closed.value) return@collect
            val connId = event.connectionId
            // Get-or-create the ConnState (minting its nonce once) and snapshot the nonce under the
            // lock; send the identity frame OUTSIDE the lock (best-effort).
            val nonce = lock.withLock { conns.getOrPut(connId) { ConnState(random.nextBytes(NONCE_BYTES)) }.nonce }
            runCatchingCancellable { api.send(connId, encodeFrame(NwHello.encode(selfId, nonce))) }
                .onFailure { log.debug { "nw.identity send failed connId=${connId.value} selfId=${selfId.value}" } }
        }
    }

    // ── loop 2: bytesReceived — demux + inline handshake ────────────────────────

    private suspend fun bytesReceivedLoop() {
        api.bytesReceived.collect { event ->
            if (closed.value) return@collect
            val connId = event.connectionId
            // Snapshot (get-or-create, minting its nonce once) the ConnState under the lock; decode OUTSIDE it.
            val cs = lock.withLock { conns.getOrPut(connId) { ConnState(random.nextBytes(NONCE_BYTES)) } }
            // The framer is single-reader (only this loop touches it), so decoding outside the lock is safe.
            for (frame in cs.framer.decode(event.bytes)) {
                processFrame(connId, cs, frame)
            }
        }
    }

    /** Outcome of classifying one frame under [lock]; the suspend action runs OUTSIDE the lock. */
    private sealed interface FrameOutcome {
        /** Already-resolved connection: [frame] is data attributed to [sender]. */
        data class Data(val sender: PeerId) : FrameOutcome

        /** Just-resolved identity: [loser] (if any) is the dedup loser to disconnect. */
        data class Resolved(val loser: NwConnectionId?) : FrameOutcome
    }

    /**
     * Handle ONE decoded frame: the first on an unresolved connection is identity; the rest are data.
     *
     * The `resolvedPeerId == null` check and the [resolveIdentity] mutation happen in the SAME
     * critical section, so [connectionClosedLoop] cannot interleave between them and re-register a
     * peer on an already-closed connection (the identity-resolution race). The suspend actions
     * ([Spool.deliver], [NwApi.disconnect]) run OUTSIDE the lock.
     */
    private suspend fun processFrame(connId: NwConnectionId, cs: ConnState, frame: ByteArray) {
        val outcome = lock.withLock {
            val resolved = cs.resolvedPeerId
            if (resolved != null) {
                FrameOutcome.Data(resolved)
            } else {
                val hello = NwHello.decode(frame)
                FrameOutcome.Resolved(resolveIdentity(connId, cs, hello.peerId, hello.nonce))
            }
        }
        when (outcome) {
            // Data frame — deliver OUTSIDE the lock (Spool.deliver suspends for backpressure).
            is FrameOutcome.Data ->
                spool.deliver(Swatch(payload = frame, sender = outcome.sender, sequence = seq.incrementAndGet()))
            // Dedup loser (if any) — disconnect OUTSIDE the lock (best-effort).
            is FrameOutcome.Resolved -> outcome.loser?.let { loserId ->
                runCatchingCancellable { api.disconnect(loserId) }
                    .onFailure { log.debug { "nw.dedup disconnect failed connId=${loserId.value}" } }
            }
        }
    }

    /**
     * Resolve [connId]'s identity to [remoteId] under [lock]. Returns the connId to disconnect (a
     * dedup loser) or `null`. Adds the peer + flips Weaving→Woven when this is the first connection
     * to [remoteId]; on a duplicate, keeps the canonical survivor (the smaller [canonicalLinkNonce]
     * of the two connections' nonces) — the peer set is unchanged either way. Direction-free: both
     * ends see the same two nonces and pick the same survivor with no coordination.
     */
    private fun resolveIdentity(
        connId: NwConnectionId,
        cs: ConnState,
        remoteId: PeerId,
        remoteNonce: ByteArray,
    ): NwConnectionId? {
        cs.resolvedPeerId = remoteId
        val canonical = canonicalLinkNonce(cs.nonce, remoteNonce)
        val existing = registry[remoteId]
        if (existing == null) {
            registry[remoteId] = Winner(connId, canonical)
            addRemotePeer(remoteId)
            return null
        }
        if (existing.connId == connId) return null // idempotent; same connection re-resolving
        // Duplicate link to remoteId. Keep the SMALLER canonical nonce; disconnect the loser.
        return if (canonical < existing.canonicalNonce) {
            registry[remoteId] = Winner(connId, canonical) // new winner; peer stays present
            conns.remove(existing.connId) // drop the displaced incumbent's state
            existing.connId // disconnect the displaced incumbent
        } else {
            conns.remove(connId) // drop this loser's state
            connId // this connection loses; disconnect it, incumbent stays
        }
    }

    /** Add [remoteId] to the peer set and flip Weaving→Woven. Called under [lock]. */
    private fun addRemotePeer(remoteId: PeerId) {
        _peers.update { it + remoteId }
        if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven
    }

    // ── loop 3: connectionClosed ────────────────────────────────────────────────

    private suspend fun connectionClosedLoop() {
        api.connectionClosed.collect { event ->
            if (closed.value) return@collect
            val tearNow = lock.withLock {
                val cs = conns.remove(event.connectionId) ?: return@withLock false
                val peer = cs.resolvedPeerId ?: return@withLock false
                // Conn-identity guard: only evict the peer if the LIVE connection is this one — a
                // stale/deduped-loser close must not evict the surviving connection to the same peer.
                if (registry[peer]?.connId != event.connectionId) return@withLock false
                registry.remove(peer)
                _peers.update { it - peer }
                // Last remote gone after having woven ⇒ the session is over (mirror the mesh rule).
                registry.isEmpty() && _state.value is SeamState.Woven
            }
            if (tearNow) latchTorn(CloseReason.RemoteRequested)
        }
    }

    // ── send ────────────────────────────────────────────────────────────────────

    override suspend fun broadcast(payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        val targets = lock.withLock { registry.values.map { it.connId } }
        val frame = encodeFrame(payload)
        for (connId in targets) {
            runCatchingCancellable { api.send(connId, frame) }
                .onFailure { removeByConn(connId) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        val connId = lock.withLock { registry[peer]?.connId } ?: throw PeerNotConnected(peer)
        runCatchingCancellable { api.send(connId, encodeFrame(payload)) }
            .onFailure { removeByConn(connId) }
    }

    /**
     * Drop a connection after a send failure, evicting its peer only if it is still the live link.
     * Mirrors [connectionClosedLoop]'s tear rule: if this was the last remote and the seam had woven,
     * tear to [CloseReason.RemoteRequested] — otherwise a send-failure eviction leaves the seam stuck
     * `Woven` with `peers == {selfId}` and [incoming] never completing, unlike a clean close.
     * The tear decision is computed under [lock]; [latchTorn] (non-suspend) runs after releasing it.
     */
    private fun removeByConn(connId: NwConnectionId) {
        val tearNow = lock.withLock {
            val cs = conns.remove(connId) ?: return@withLock false
            val peer = cs.resolvedPeerId ?: return@withLock false
            if (registry[peer]?.connId != connId) return@withLock false
            registry.remove(peer)
            _peers.update { it - peer }
            // Last remote gone after having woven ⇒ the session is over (mirror the close rule).
            registry.isEmpty() && _state.value is SeamState.Woven
        }
        if (tearNow) latchTorn(CloseReason.RemoteRequested)
    }

    // ── close ─────────────────────────────────────────────────────────────────

    override suspend fun close(reason: CloseReason) {
        // Single-shot: if a self-driven Torn (last-peer drop) already fired, this no-ops.
        if (!latchTorn(reason)) return
        val targets = lock.withLock {
            val snapshot = registry.values.map { it.connId }
            registry.clear()
            conns.clear()
            _peers.value = setOf(selfId)
            snapshot
        }
        for (connId in targets) {
            runCatchingCancellable { api.disconnect(connId) }
        }
    }

    /**
     * Terminal teardown, latched exactly once via [closed]. Publishes [SeamState.Torn], completes
     * [incoming] by closing the [spool], and cancels [scope] (stopping all three collectors).
     * Returns `false` if teardown already ran. Called from [close] (local) and [connectionClosedLoop]
     * (last remote gone).
     */
    private fun latchTorn(reason: CloseReason): Boolean {
        if (!closed.compareAndSet(expect = false, update = true)) return false
        _state.value = SeamState.Torn(reason)
        spool.close()
        scope.coroutineContext[Job]?.cancel()
        return true
    }
}
