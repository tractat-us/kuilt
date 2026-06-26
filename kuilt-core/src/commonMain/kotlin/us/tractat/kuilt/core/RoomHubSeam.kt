package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Server-side hub [Seam] for one named room — the structural per-room isolation primitive.
 *
 * A [RoomHubSeam] is the server's view of a single room. A frame broadcast here reaches
 * **only** the connections that have been admitted to this room; a non-member is never in
 * the fanout list, so a cross-room leak is structurally unrepresentable — isolation by
 * construction, not by guard.
 *
 * ## Membership / registration
 *
 * A connection joins a room via two gates:
 * 1. **Authorization** — [authorizer] is invoked with the peer's id and this room's
 *    [channelName] on the first inbound frame. A `false` return structurally excludes
 *    the connection: it is never added to [peers] or the fanout.
 * 2. **First-frame admission** — only if the authorizer returns `true` is the connection
 *    registered. All subsequent frames from that connection on this channel are then
 *    forwarded to [incoming] and the connection appears in [peers].
 *
 * A connection is automatically deregistered when its channel flow completes (underlying
 * link torn).
 *
 * ## Thread safety
 *
 * All mutable state ([registered]) is guarded by a reentrant lock. Suspend calls
 * (authorizer, sends, broadcasts, flow emissions) are always performed **outside** the lock.
 *
 * @param channelName the room name, matching the [NamedMux] channel tag clients use.
 * @param selfId this server peer's own [PeerId].
 * @param scope coroutine scope for per-connection collection jobs.
 * @param authorizer required authorization policy — invoked on first frame from each
 *   connection. Use [RoomAuthorizer.AllowAll] for open-access rooms and in tests.
 */
public class RoomHubSeam(
    internal val channelName: String,
    override val selfId: PeerId,
    private val scope: CoroutineScope,
    private val authorizer: RoomAuthorizer,
) : Seam {

    private val lock = reentrantLock()

    /** Registered (authorized) connections: peerId → channel-seam for that connection. */
    private val registered = mutableMapOf<PeerId, Seam>()

    private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    /** Merged incoming: frames from all registered connections forwarded here. */
    private val _incoming = MutableSharedFlow<Swatch>(
        replay = 0,
        extraBufferCapacity = DeliveryPolicy.DEFAULT_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<Swatch> = _incoming.asSharedFlow()

    /**
     * Start tracking [connPeerId] for potential membership in this room.
     *
     * Launches a coroutine that collects from [channelSeam.incoming]. On the first frame:
     * 1. [authorizer] is consulted — if it rejects, the collection stops and the
     *    connection is structurally excluded (never appears in [peers] or the fanout).
     * 2. If admitted, [connPeerId] is added to [peers] and all frames (including the
     *    first) are forwarded to [incoming].
     *
     * When [channelSeam] tears (flow completion), [connPeerId] is deregistered from [peers].
     *
     * Thread-safe. Called by [MuxServerLoom] for each new connection and for each new room.
     */
    internal fun trackConnection(connPeerId: PeerId, channelSeam: Seam) {
        scope.launch {
            var admitted = false
            channelSeam.incoming.collect { frame ->
                if (!admitted) {
                    val allowed = authorizer.authorize(connPeerId, channelName)
                    if (!allowed) return@collect
                    lock.withLock {
                        registered[connPeerId] = channelSeam
                        _peers.update { it + connPeerId }
                    }
                    admitted = true
                }
                _incoming.emit(frame)
            }
        }.invokeOnCompletion {
            // Deregister only if THIS channelSeam is still the registered one. A reconnect
            // (same PeerId over a fresh connection) may have already replaced the entry; the
            // stale tracker's completion must not evict the live re-registered membership.
            lock.withLock {
                if (registered[connPeerId] === channelSeam) {
                    registered.remove(connPeerId)
                    _peers.update { it - connPeerId }
                }
            }
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        checkOpen()
        val targets = lock.withLock { registered.values.toList() }
        targets.forEach { channelSeam ->
            runCatchingCancellable { channelSeam.broadcast(payload) }
                .onFailure { /* best-effort: torn spoke — ignore */ }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkOpen()
        val target = lock.withLock { registered[peer] }
            ?: throw PeerNotConnected(peer)
        runCatchingCancellable { target.broadcast(payload) }
            .onFailure { /* best-effort: torn spoke */ }
    }

    override suspend fun close(reason: CloseReason) {
        if (_state.value is SeamState.Torn) return
        _state.value = SeamState.Torn(reason)
        lock.withLock {
            registered.clear()
            _peers.value = emptySet()
        }
    }

    private fun checkOpen() {
        check(_state.value !is SeamState.Torn) { "RoomHubSeam for '$channelName' is closed" }
    }
}
