package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.meshSeam
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * A server-side [Loom] that provides **structural per-room isolation** over a shared
 * [ConnectionSource].
 *
 * Each accepted [us.tractat.kuilt.core.fabric.Connection] is wrapped in a [NamedMux].
 * [host] for a given room name returns a [RoomHubSeam] — a server-centred star [Seam]
 * that forwards broadcasts **only** to connections admitted to that room. A connection
 * joins a room when its first frame arrives on that room's channel AND [authorizer]
 * grants admission. A non-member is never in the fanout list — isolation is by
 * construction, not by runtime guard.
 *
 * ## Usage
 *
 * ```kotlin
 * val serverLoom = MuxServerLoom(
 *     source = source,
 *     scope = scope,
 *     selfId = PeerId("server"),
 *     authorizer = RoomAuthorizer { peer, tag -> sessionStore.isAdmitted(peer, tag) },
 * )
 * val room7 = serverLoom.host(Pattern("table-7"))
 * val room9 = serverLoom.host(Pattern("table-9"))
 * ```
 *
 * ## Architecture
 *
 * The accept pump runs on a child [SupervisorJob] scope so individual-spoke failures
 * don't cancel the pump. For each accepted connection:
 * 1. A 2-peer [Seam] is built via [meshSeam].
 * 2. A [NamedMux] is wrapped around it, keyed by the connection's [PeerId].
 * 3. Every existing [RoomHubSeam] starts tracking the new connection's per-room channel.
 *
 * When [host] creates a new room after connections are already alive, every live
 * connection starts tracking for the new room immediately.
 *
 * ## Reconnect / resume (server side)
 *
 * Membership is keyed by [PeerId], not by the underlying connection. When a connection
 * with a previously-seen [PeerId] is accepted (a reconnect over a fresh transport with
 * the same identity), its per-room channel is re-tracked into every room. Because
 * [RoomHubSeam] keys its registration map by [PeerId], the returning connection lands
 * back in exactly the rooms it was in before — same tag, same fanout membership — rather
 * than as a brand-new peer. Client-side resume (re-emitting tags after a drop) lives in a
 * separate concern; this [Loom] handles only the server-side re-association.
 *
 * [join] throws [UnsupportedOperationException] — this is a server-only [Loom].
 *
 * ## Thread safety
 *
 * [connMuxes] and [rooms] are guarded by [lock]. Suspend calls are always outside the lock.
 *
 * @param source accept source for incoming client connections.
 * @param scope scope for the accept pump and per-room tracking coroutines.
 * @param selfId this server's own [PeerId].
 * @param authorizer required authorization policy for per-room membership. Invoked on the
 *   first inbound frame per (connection, room) pair; a `false` return structurally excludes
 *   the connection from that room. Use [RoomAuthorizer.AllowAll] for open-access servers.
 *   Required (no default) per the "optional ≠ tuning" rule — absent it the gate would be
 *   silently disabled.
 * @param dispatcher coroutine context for mesh-seam link loops. Defaults to the interceptor
 *   from [scope] so virtual-time tests share the test dispatcher automatically.
 * @param random seeded [Random] for mesh-seam nonce generation.
 */
public class MuxServerLoom(
    private val source: ConnectionSource,
    scope: CoroutineScope,
    public val selfId: PeerId,
    private val authorizer: RoomAuthorizer,
    private val dispatcher: CoroutineContext = requireNotNull(scope.coroutineContext[ContinuationInterceptor]) {
        "MuxServerLoom scope must have a ContinuationInterceptor (dispatcher)"
    },
    private val random: Random = Random.Default,
) : Loom {

    private val lock = reentrantLock()

    /** Live connections: peerId → NamedMux. Replaced on reconnect; removed when the link tears. */
    private val connMuxes = mutableMapOf<PeerId, NamedMux>()

    /** Hosted rooms: channelName → RoomHubSeam. Created on the first [host] call per name. */
    private val rooms = mutableMapOf<String, RoomHubSeam>()

    private val pumpScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    init {
        pumpScope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        while (coroutineContext.isActive) {
            val conn = source.accept()
            runCatchingCancellable { admit(conn) }
                .onFailure { /* best-effort: torn/garbled spoke — drop and keep accepting */ }
        }
    }

    /** Handshake one accepted connection into a per-peer [NamedMux] and track it in every room. */
    private suspend fun admit(conn: Connection) {
        val rawSeam = meshSeam(
            selfId = selfId,
            connections = listOf(conn),
            dispatcher = dispatcher,
            random = random,
        )
        val connPeerId = rawSeam.peers.first { peers -> peers.any { it != selfId } }.first { it != selfId }
        val mux = NamedMux(rawSeam, pumpScope)
        val existingRooms = lock.withLock {
            connMuxes[connPeerId] = mux
            rooms.values.toList()
        }
        // Re-association by PeerId: every room re-tracks this connPeerId. A returning peer
        // (same id, new connection) lands back in the rooms it was previously a member of.
        existingRooms.forEach { room ->
            room.trackConnection(connPeerId, mux.channel(room.channelName))
        }
        // Clean up the connMuxes entry when the link tears — but only if it is still THIS mux
        // (a reconnect may have already replaced it with a fresher one).
        pumpScope.launch {
            rawSeam.state.collect { state ->
                if (state is SeamState.Torn) {
                    lock.withLock { connMuxes.remove(connPeerId, mux) }
                }
            }
        }
    }

    override suspend fun weave(rendezvous: Rendezvous): Seam = when (rendezvous) {
        is Rendezvous.New -> roomFor(rendezvous.pattern.displayName)
        is Rendezvous.Existing -> throw UnsupportedOperationException(
            "MuxServerLoom is a server-only Loom — use a client-side Loom to join",
        )
    }

    private fun roomFor(channelName: String): RoomHubSeam {
        val (room, snapshot) = lock.withLock {
            val hub = rooms.getOrPut(channelName) {
                RoomHubSeam(
                    channelName = channelName,
                    selfId = selfId,
                    scope = pumpScope,
                    authorizer = authorizer,
                )
            }
            hub to connMuxes.toList()
        }
        // Register existing connections on the new room — outside the lock.
        snapshot.forEach { (peerId, mux) ->
            room.trackConnection(peerId, mux.channel(channelName))
        }
        return room
    }
}
