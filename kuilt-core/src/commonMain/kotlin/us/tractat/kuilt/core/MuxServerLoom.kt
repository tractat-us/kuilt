package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.acceptPump
import us.tractat.kuilt.core.fabric.hubMesh
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * ## Lifecycle
 *
 * A [ScopedCloseable]: the accept loop and every per-connection read/watch pump run in an owned
 * child scope whose job is a **child** of [scope]'s job. Cancelling [scope] therefore stops every
 * pump, and [close] tears the loom down explicitly — it halts the accept loop, cancels all
 * per-connection pumps, and (via each read loop's teardown) deregisters the connection from the
 * rooms it joined. [close] is **idempotent**. It does **not** tear the hosted [RoomHubSeam]s or the
 * registered per-connection mesh seams: those have independent lifecycles owned by their consumers
 * and by the [source]/connection provider respectively. A temporary mesh that admission abandons
 * before registration is closed by the loom, including when admission is cancelled.
 *
 * A hosted room's own `close` takes it out of this loom, so the next [host] of that name opens a
 * fresh room (#3041). **Caller contract:** finish closing the old room before re-hosting its name.
 * [host] is idempotent per name, so a [host] of a name whose room is still open returns that room,
 * even one about to close; only a [host] arriving after the room's `close` has begun gets a fresh one. A connection whose link outlived the old room carries nothing into the new one:
 * its next frame on the channel is a first frame there, authorized and admitted like any other.
 *
 * ## Thread safety
 *
 * [connRecords], [rooms], and [launchedJobs] are guarded by [lock]. Suspend calls are always
 * outside the lock.
 *
 * @param source accept source for incoming client connections.
 * @param scope parent scope for the loom. The owned pump scope (accept loop + per-connection read
 *   loops + tracking coroutines) is a child of it, so cancelling [scope] — or calling [close] —
 *   stops every pump.
 * @param selfId this server's own [PeerId].
 * @param authorizer required authorization policy for per-room membership. Invoked on the
 *   first inbound frame per (connection, room) pair; a `false` return structurally excludes
 *   the connection from that room. Use [RoomAuthorizer.AllowAll] for open-access servers.
 *   Required (no default) per the "optional ≠ tuning" rule — absent it the gate would be
 *   silently disabled.
 * @param dispatcher coroutine context for mesh-seam link loops. Defaults to the interceptor
 *   from [scope] so virtual-time tests share the test dispatcher automatically.
 * @param random seeded [Random] for mesh-seam nonce generation.
 * @param handshakeTimeout ceiling on a single accepted connection's handshake+admit; a connection
 *   that connects but never completes its `MeshHello` handshake is abandoned and closed after this,
 *   so it can never wedge the accept loop. Each accepted connection is admitted concurrently in its
 *   own child coroutine (all shared state is lock-guarded), so a slow handshake never starves later
 *   connections either.
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
    private val handshakeTimeout: Duration = 10.seconds,
) : Loom, ScopedCloseable(scope) {

    private val lock = reentrantLock()

    /** Live connections: peerId → record. Replaced on reconnect; removed when the link tears. */
    private val connRecords = mutableMapOf<PeerId, ConnRecord>()

    private val _connectedPeers = MutableStateFlow<Set<PeerId>>(emptySet())

    /**
     * The set of remote [PeerId]s with a live connection to this server — every peer whose link has
     * been accepted and handshaked and whose read pump has not yet torn down. A peer appears the
     * moment its connection is admitted and disappears when its link tears (or on [close]).
     *
     * Generic loom-level connection observability, independent of any room membership: a peer is in
     * this set as soon as it connects, before it has announced itself into any room. Kept in step
     * with [connRecords] under [lock] — a reconnect (same [PeerId] over a fresh transport) does not
     * perturb the set, and a superseded record's later teardown does not spuriously remove a resumed
     * peer (teardown removes only when the current record still matches).
     *
     * A single [MutableStateFlow] read is safe to observe from any collector; per the single-emission
     * contract a downstream that needs to react to appear/disappear collects it once. `:kuilt-cluster`
     * consumes this via `attachConnections` to publish each connected player into the overlay's
     * attachment directory — this loom itself depends on nothing in that layer.
     */
    public val connectedPeers: StateFlow<Set<PeerId>> = _connectedPeers.asStateFlow()

    /**
     * Open rooms: channelName → RoomHubSeam. A hub enters on the first [host] of its name and leaves
     * when it closes ([forget]), so every entry is a live room and a name is free again once its room
     * closes (#3041).
     */
    private val rooms = mutableMapOf<String, RoomHubSeam>()

    /**
     * Every coroutine launched into the owned [scope]: the accept loop plus the per-connection
     * read/watch pumps. Tracked only so lifecycle tests can observe pumps stop on [close] /
     * parent-scope cancellation; guarded by [lock].
     */
    private val launchedJobs = mutableListOf<Job>()

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
                object : OutboundSender {
                    override suspend fun send(payload: ByteArray) =
                        rawSeam.broadcast(NamedFrame.encode(nameBytes, payload))

                    /**
                     * This connection's ceiling, less the room header [send] prepends (#2058) — the
                     * per-connection input to the hub's fold. Read live rather than captured: the
                     * connection's seam publishes a number that can move under it.
                     *
                     * An object expression rather than the SAM lambda this used to be, because a
                     * SAM conversion can only supply the abstract member and would leave the budget
                     * at [OutboundSender]'s `null` default — the hub would then fold nothing, and a
                     * room over a bounded fabric would keep reporting "unknown".
                     */
                    override val maxPayloadBytes: Int?
                        get() = rawSeam.maxPayloadBytes
                            ?.let { (it - NamedFrame.headerBytesFor(nameBytes)).coerceAtLeast(0) }
                }
            }
        }

        /** Snapshot of all per-room senders created so far (for teardown deregistration). */
        fun knownSenders(): Map<String, OutboundSender> = senderLock.withLock { senders.toMap() }
    }

    init {
        startAcceptLoop()
    }

    /**
     * Launch the accept loop into the owned [scope]. Kept in a member function (not the `init`
     * block) so the bare `scope` reference resolves to the inherited owned pump scope — inside an
     * `init` block the constructor parameter `scope` (the *parent*) would shadow it.
     */
    private fun startAcceptLoop() {
        val job = scope.acceptPump(
            source = source,
            handshakeTimeout = handshakeTimeout,
            onFailure = { /* best-effort: accept-source failure or torn/garbled/slow spoke — keep accepting */ },
            handle = { conn -> admit(conn) },
        )
        lock.withLock { launchedJobs += job }
    }

    /** Snapshot of every pump this loom has launched into its owned scope — for lifecycle tests. */
    internal val backgroundJobsForTest: List<Job> get() = lock.withLock { launchedJobs.toList() }

    /**
     * Handshake one accepted connection, then read it exactly once: each inbound frame is
     * demuxed by channel name and pushed into the matching live [RoomHubSeam].
     */
    private suspend fun admit(conn: Connection) {
        // The mesh starts empty and takes the conn through `addLink`, which owns it from entry: an
        // exit before adoption closes it, and an adopted link is closed with the seam below. Building
        // the mesh around the conn instead would hand a handshaken link back through `buildMesh`'s
        // `awaitAll`, where a cancellation drops it with nothing left holding it.
        var rawSeam: Mesh? = null
        var registered = false
        try {
            val seam = hubMesh(
                selfId = selfId,
                connections = emptyList(),
                dispatcher = dispatcher,
                random = random,
            )
            rawSeam = seam
            seam.addLink(conn)
            val connPeerId = seam.peers.first { peers -> peers.any { it != selfId } }.first { it != selfId }
            val record = ConnRecord(seam)
            lock.withLock {
                connRecords[connPeerId] = record
                _connectedPeers.value = connRecords.keys.toSet()
                registered = true
            }

            // Enter readLoop's teardown guard even if the loom is cancelled before dispatch.
            // Once registered, the existing provider-owned seam lifecycle applies.
            val readJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { readLoop(connPeerId, record) }
            val watchJob = scope.launch { watchDrop(connPeerId, record) }
            lock.withLock {
                launchedJobs += readJob
                launchedJobs += watchJob
            }
        } finally {
            val built = rawSeam
            if (!registered) withContext(NonCancellable) {
                try {
                    // No mesh means `addLink` was never entered (building the empty mesh was
                    // cancelled), so the conn is still this frame's to close.
                    if (built != null) built.close() else conn.close()
                } catch (_: Throwable) {
                    // Preserve the original admission failure, including cancellation.
                }
            }
        }
    }

    /**
     * The single collection of one connection's inbound stream. Each frame is demuxed by its
     * [NamedFrame] name header and pushed into the matching live room (if any). When the seam
     * tears, the connection is deregistered from every room it joined.
     */
    private suspend fun readLoop(connPeerId: PeerId, record: ConnRecord) {
        // The host-verified principal for this connection, read from the per-connection mesh that
        // admitted the link during `admit()` (before this read loop runs) — so it is already
        // populated. Null when the connection carried no attestation.
        val principal = (record.rawSeam as? PrincipalRoster)?.attestedPrincipals?.value?.get(connPeerId)
        try {
            // Keep the undispatched prefix inside this teardown guard, but yield before reading
            // buffered frames: synchronous authorizer work must not extend the admitting caller's
            // handshake timeout. Cancellation at this dispatch boundary still deregisters the record.
            yield()
            record.rawSeam.incoming.collect { frame ->
                val name = NamedFrame.decodeName(frame) ?: return@collect
                val room = lock.withLock { rooms[name] } ?: return@collect
                room.deliver(connPeerId, NamedFrame.strip(frame), record.senderFor(name), principal)
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
            if (connRecords[connPeerId] === record) {
                connRecords.remove(connPeerId)
                _connectedPeers.value = connRecords.keys.toSet()
            }
        }
    }

    /**
     * [FabricAvailability.Available] is a **fact** here, not the guess `Loom.capability()`'s floor
     * exists to refuse (#1746). An accept-side loom reaches no remote and acquires no OS resource
     * at weave time: [weave] hands back a room hub over the [ConnectionSource] the caller already
     * built and passed in, so "is this fabric attemptable on this runtime" is settled by
     * construction. (`KtorServerLoom` in `:kuilt-websocket` is the same shape.) Roleless because
     * the transport's roles belong to whatever [ConnectionSource] was supplied, which this loom
     * does not inspect.
     */
    override fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    override suspend fun weave(rendezvous: Rendezvous): Seam = when (rendezvous) {
        is Rendezvous.New -> roomFor(rendezvous.pattern.sessionName)
        is Rendezvous.Existing -> throw UnsupportedOperationException(
            "MuxServerLoom is a server-only Loom — use a client-side Loom to join",
        )
    }

    /**
     * Idempotent while the room is open: the [RoomHubSeam] for [channelName], created on first
     * request and again on the first request after it closes. Connections register lazily — as
     * each demuxed frame for [channelName] arrives, [readLoop] pushes it into this room, which
     * admits the peer on its first frame.
     */
    private fun roomFor(channelName: String): RoomHubSeam = lock.withLock {
        rooms.getOrPut(channelName) {
            RoomHubSeam(
                channelName = channelName,
                selfId = selfId,
                authorizer = authorizer,
                onClose = ::forget,
            )
        }
    }

    /**
     * Drop a closing [hub] from [rooms], **by identity**: a newer hub already hosted under the same
     * name is never evicted by its predecessor's close, including a repeat one.
     */
    private fun forget(hub: RoomHubSeam) {
        lock.withLock {
            if (rooms[hub.channelName] === hub) rooms.remove(hub.channelName)
        }
    }
}
