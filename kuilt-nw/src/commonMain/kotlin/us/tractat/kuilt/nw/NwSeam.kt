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
 *  1. **connectionOpened** — records each connection's direction (`outbound = endpoint != null`,
 *     i.e. we dialled it) and sends our identity frame.
 *  2. **bytesReceived** — the demux + inline handshake: the first decoded frame on an unresolved
 *     connection is the remote's [PeerId]; every later frame is data, delivered to [incoming]
 *     stamped with that sender.
 *  3. **connectionClosed** — evicts the peer (conn-identity guarded so a deduped loser's close
 *     can't evict the survivor) and tears the seam when the last remote drops.
 *
 * ## Duplicate-dial dedup (lower-id dialer wins)
 * A full mesh double-dials each pair, producing two connections to the same peer. When a
 * connection's identity resolves to `remoteId` and another connection to `remoteId` already
 * exists, the survivor is the one **dialled by the lower-id peer**: a connection survives iff
 * `(outbound && selfId < remoteId) || (!outbound && remoteId < selfId)`. Both ends see the same
 * `{selfId, remoteId}` pair with inverted directions, so both compute the same survivor with no
 * coordination; the loser is disconnected and its later close is a no-op (conn-identity guard).
 *
 * ## Ordering invariant
 * `connectionOpened(connId)` is observed before any `bytesReceived(connId)` — a connection must
 * exist to carry bytes (true in the real fabric and [FakeNwRadio]). The [ConnState] is
 * get-or-created in BOTH loops so an interleave can't lose it, but direction is authoritative
 * only once `connectionOpened` has set it. Should identity ever resolve before direction is
 * known (invariant violated), dedup keeps the already-registered connection and disconnects the
 * newcomer rather than trusting a defaulted direction.
 *
 * ## Thread-safety
 * The [registry] and [conns] maps are shared across the three collectors (each `collect` is
 * internally sequential, but they run concurrently) and the caller-driven [broadcast]/[sendTo].
 * All map access is guarded by one [reentrantLock] (atomicfu). **No `suspend`/`api.*` call ever
 * runs under the lock** — targets are snapshotted under the lock, then sent/disconnected/delivered
 * outside it. Correct under a multi-threaded dispatcher; no single-thread-confinement crutch.
 *
 * @param selfId this peer's stable identity, sent as the first framed message on each connection.
 * @param api    the transport moving raw bytes over open connections.
 * @param scope  coroutine scope hosting the three collectors; cancelled on teardown.
 * @param policy delivery policy for the inbound [Spool] (default [DeliveryPolicy.Reliable]).
 */
internal class NwSeam(
    override val selfId: PeerId,
    private val api: NwApi,
    private val scope: CoroutineScope,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Seam {

    /** Per-connection mutable state. All fields read/written only under [lock]; [framer] is driven only by the single bytes loop. */
    private class ConnState {
        val framer: NwFramer = NwFramer()
        var resolvedPeerId: PeerId? = null
        var outbound: Boolean = false
        var directionKnown: Boolean = false
    }

    private val lock = reentrantLock()

    /** Resolved remote identity → the live connection carrying it. */
    private val registry = mutableMapOf<PeerId, NwConnectionId>()

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
            lock.withLock {
                val cs = conns.getOrPut(connId) { ConnState() }
                // outbound ⇔ we dialled it (a dialled connection carries the dialled endpoint).
                cs.outbound = event.endpoint != null
                cs.directionKnown = true
            }
            // Send our identity frame OUTSIDE the lock (best-effort).
            runCatchingCancellable { api.send(connId, encodeFrame(selfId.value.encodeToByteArray())) }
                .onFailure { log.debug { "nw.identity send failed connId=${connId.value} selfId=${selfId.value}" } }
        }
    }

    // ── loop 2: bytesReceived — demux + inline handshake ────────────────────────

    private suspend fun bytesReceivedLoop() {
        api.bytesReceived.collect { event ->
            if (closed.value) return@collect
            val connId = event.connectionId
            // Snapshot (get-or-create) the ConnState under the lock; decode OUTSIDE it.
            val cs = lock.withLock { conns.getOrPut(connId) { ConnState() } }
            // The framer is single-reader (only this loop touches it), so decoding outside the lock is safe.
            for (frame in cs.framer.decode(event.bytes)) {
                processFrame(connId, cs, frame)
            }
        }
    }

    /** Handle ONE decoded frame: the first on an unresolved connection is identity; the rest are data. */
    private suspend fun processFrame(connId: NwConnectionId, cs: ConnState, frame: ByteArray) {
        val alreadyResolved = lock.withLock { cs.resolvedPeerId }
        if (alreadyResolved == null) {
            val remoteId = PeerId(frame.decodeToString())
            val loser = lock.withLock { resolveIdentity(connId, cs, remoteId) }
            loser?.let { loserId ->
                runCatchingCancellable { api.disconnect(loserId) }
                    .onFailure { log.debug { "nw.dedup disconnect failed connId=${loserId.value}" } }
            }
        } else {
            // Data frame — deliver OUTSIDE the lock (Spool.deliver suspends for backpressure).
            spool.deliver(Swatch(payload = frame, sender = alreadyResolved, sequence = seq.incrementAndGet()))
        }
    }

    /**
     * Resolve [connId]'s identity to [remoteId] under [lock]. Returns the connId to disconnect (a
     * dedup loser) or `null`. Adds the peer + flips Weaving→Woven when this is the first connection
     * to [remoteId]; on a duplicate, keeps the canonical survivor (lower-id dialer) — the peer set
     * is unchanged either way.
     */
    private fun resolveIdentity(connId: NwConnectionId, cs: ConnState, remoteId: PeerId): NwConnectionId? {
        cs.resolvedPeerId = remoteId
        val existing = registry[remoteId]
        if (existing == null || existing == connId) {
            registry[remoteId] = connId
            addRemotePeer(remoteId)
            return null
        }
        // Duplicate link to remoteId. Keep the canonical survivor; disconnect the loser. If this
        // connection's direction isn't known yet (invariant violated), keep the incumbent.
        val thisSurvives = cs.directionKnown && survives(cs.outbound, remoteId)
        return if (thisSurvives) {
            registry[remoteId] = connId // new winner; peer stays present
            conns.remove(existing) // drop the displaced incumbent's state
            existing // disconnect the displaced incumbent
        } else {
            conns.remove(connId) // drop this loser's state
            connId // this connection loses; disconnect it, incumbent stays
        }
    }

    /** A connection survives dedup iff it was dialled by the lower-id peer. Called under [lock]. */
    private fun survives(outbound: Boolean, remoteId: PeerId): Boolean =
        (outbound && selfId.value < remoteId.value) || (!outbound && remoteId.value < selfId.value)

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
                if (registry[peer] != event.connectionId) return@withLock false
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
        val targets = lock.withLock { registry.values.toList() }
        val frame = encodeFrame(payload)
        for (connId in targets) {
            runCatchingCancellable { api.send(connId, frame) }
                .onFailure { removeByConn(connId) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        val connId = lock.withLock { registry[peer] } ?: throw PeerNotConnected(peer)
        runCatchingCancellable { api.send(connId, encodeFrame(payload)) }
            .onFailure { removeByConn(connId) }
    }

    /** Drop a connection after a send failure, evicting its peer only if it is still the live link. */
    private fun removeByConn(connId: NwConnectionId) {
        lock.withLock {
            val cs = conns.remove(connId) ?: return@withLock
            val peer = cs.resolvedPeerId ?: return@withLock
            if (registry[peer] != connId) return@withLock
            registry.remove(peer)
            _peers.update { it - peer }
        }
    }

    // ── close ─────────────────────────────────────────────────────────────────

    override suspend fun close(reason: CloseReason) {
        // Single-shot: if a self-driven Torn (last-peer drop) already fired, this no-ops.
        if (!latchTorn(reason)) return
        val targets = lock.withLock {
            val snapshot = registry.values.toList()
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
