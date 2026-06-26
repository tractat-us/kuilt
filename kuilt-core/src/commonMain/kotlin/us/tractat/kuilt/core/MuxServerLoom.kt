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
 * Each accepted [Connection] is handshaked into a 2-peer [Seam] and read **exactly once**;
 * inbound frames are demultiplexed by channel name (the [NamedFrame] wire header) and pushed
 * into the matching [RoomHubSeam]. [host] for a given room name returns that [RoomHubSeam] — a
 * server-centred star [Seam] that forwards broadcasts **only** to connections admitted to that
 * room. A connection joins a room when its first frame arrives on that room's channel AND
 * [authorizer] grants admission. A non-member is never in the fanout list — isolation is by
 * construction, not by runtime guard.
 *
 * ## Why single-collection-and-demux
 *
 * Routing per-room registration through a [NamedMux] channel view (a replay-0 `shareIn` plus a
 * per-view pipe) makes registration depend on subscription timing — a frame can arrive before
 * the room's collector subscribes and be lost, leaving a peer permanently unregistered. Reading
 * each connection's seam once and pushing demuxed frames into a bounded [Spool] inside each room
 * removes that race: the buffered spool retains a frame delivered before the room's consumer
 * subscribes, so registration and fanout are deterministic under virtual time.
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
 * ## Reconnect / resume (server side)
 *
 * Membership is keyed by [PeerId], not by the underlying connection. When a connection with a
 * previously-seen [PeerId] is accepted (a reconnect over a fresh transport with the same
 * identity), its demuxed frames re-register it into whatever room its tags name. Because
 * [RoomHubSeam] keys its registration map by [PeerId] and compares the outbound handle by
 * identity, the returning connection lands back in exactly the rooms it re-announces, and the
 * dropped connection's later teardown does **not** evict the resumed membership. Client-side
 * resume (re-emitting tags after a drop) lives in a separate concern; this [Loom] handles only
 * the server-side re-association.
 *
 * [join] throws [UnsupportedOperationException] — this is a server-only [Loom].
 *
 * ## Thread safety
 *
 * [connRecords] and [rooms] are guarded by [lock]. Suspend calls are always outside the lock.
 *
 * @param source accept source for incoming client connections.
 * @param scope scope for the accept pump, per-connection read loops, and tracking coroutines.
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

    /** Live connections: peerId → record. Replaced on reconnect; removed when the link tears. */
    private val connRecords = mutableMapOf<PeerId, ConnRecord>()

    /** Hosted rooms: channelName → RoomHubSeam. Created on the first [host] call per name. */
    private val rooms = mutableMapOf<String, RoomHubSeam>()

    private val pumpScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    /**
     * One live connection: the handshaked [rawSeam] plus a cache of per-room [OutboundSender]s
     * so the same handle instance is reused across frames (identity-stable for reconnect-safe
     * deregister).
     *
     * The server does **not** wrap [rawSeam] in a [NamedMux]: a `NamedMux` would collect
     * `rawSeam.incoming` for its own shared upstream, stealing the single collection the
     * [readLoop] needs. Outbound framing is the trivial direction — the sender simply prefixes
     * the [NamedFrame] header and broadcasts on [rawSeam] directly.
     */
    private inner class ConnRecord(val rawSeam: Seam) {
        private val senderLock = reentrantLock()
        private val senders = mutableMapOf<String, OutboundSender>()

        /** Idempotent: the outbound sender for [channelName] on this connection. */
        fun senderFor(channelName: String): OutboundSender = senderLock.withLock {
            senders.getOrPut(channelName) {
                val nameBytes = channelName.encodeToByteArray()
                OutboundSender { payload -> rawSeam.broadcast(NamedFrame.encode(nameBytes, payload)) }
            }
        }

        /** Snapshot of all per-room senders created so far (for teardown deregistration). */
        fun knownSenders(): Map<String, OutboundSender> = senderLock.withLock { senders.toMap() }
    }

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

    /**
     * Handshake one accepted connection, then read it exactly once: each inbound frame is
     * demuxed by channel name and pushed into the matching live [RoomHubSeam].
     */
    private suspend fun admit(conn: Connection) {
        val rawSeam = meshSeam(
            selfId = selfId,
            connections = listOf(conn),
            dispatcher = dispatcher,
            random = random,
        )
        val connPeerId = rawSeam.peers.first { peers -> peers.any { it != selfId } }.first { it != selfId }
        val record = ConnRecord(rawSeam)
        lock.withLock { connRecords[connPeerId] = record }

        pumpScope.launch { readLoop(connPeerId, record) }
        pumpScope.launch { watchDrop(connPeerId, record) }
    }

    /**
     * The single collection of one connection's inbound stream. Each frame is demuxed by its
     * [NamedFrame] name header and pushed into the matching live room (if any). When the seam
     * tears, the connection is deregistered from every room it joined.
     */
    private suspend fun readLoop(connPeerId: PeerId, record: ConnRecord) {
        try {
            record.rawSeam.incoming.collect { frame ->
                val name = NamedFrame.decodeName(frame) ?: return@collect
                val room = lock.withLock { rooms[name] } ?: return@collect
                room.deliver(connPeerId, NamedFrame.strip(frame), record.senderFor(name))
            }
        } finally {
            teardownConnection(connPeerId, record)
        }
    }

    /**
     * Detect a dropped connection. A single-peer mesh that loses its only remote peer (the link
     * tore) does not by itself complete [Seam.incoming], so the [readLoop] would linger. Close
     * the seam on peer-loss to complete the read loop and drive [teardownConnection], so the
     * connection's stale membership is deregistered promptly — and a reconnect's re-association
     * is never masked by a lingering dead registration.
     */
    private suspend fun watchDrop(connPeerId: PeerId, record: ConnRecord) {
        record.rawSeam.peers.first { connPeerId !in it }
        runCatchingCancellable { record.rawSeam.close() }
    }

    /** Deregister [record] from every room it joined and drop it from [connRecords] (if still current). */
    private fun teardownConnection(connPeerId: PeerId, record: ConnRecord) {
        val (snapshot, knownSenders) = lock.withLock {
            rooms.toMap() to record.knownSenders()
        }
        knownSenders.forEach { (channelName, sender) ->
            snapshot[channelName]?.deregister(connPeerId, sender)
        }
        lock.withLock {
            if (connRecords[connPeerId] === record) connRecords.remove(connPeerId)
        }
    }

    override suspend fun weave(rendezvous: Rendezvous): Seam = when (rendezvous) {
        is Rendezvous.New -> roomFor(rendezvous.pattern.displayName)
        is Rendezvous.Existing -> throw UnsupportedOperationException(
            "MuxServerLoom is a server-only Loom — use a client-side Loom to join",
        )
    }

    /**
     * Idempotent: the [RoomHubSeam] for [channelName], created on first request. Connections
     * register lazily — as each demuxed frame for [channelName] arrives, [readLoop] pushes it
     * into this room, which admits the peer on its first frame.
     */
    private fun roomFor(channelName: String): RoomHubSeam = lock.withLock {
        rooms.getOrPut(channelName) {
            RoomHubSeam(
                channelName = channelName,
                selfId = selfId,
                authorizer = authorizer,
            )
        }
    }
}
