package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.coroutines.CoroutineContext

/**
 * Build a fully-connected N-peer mesh [Seam] from a set of raw point-to-point
 * connections.
 *
 * For each [Conn] in [conns], [meshSeam] exchanges a [Hello] preamble to learn
 * the remote [PeerId]. Both sides of a link exchange their [Hello] concurrently,
 * so all [Hello] exchanges in [conns] run in parallel — this function suspends
 * until every handshake completes.
 *
 * **Dedup:** if two conns learn the same remote id (i.e. duplicate links to the
 * same peer), the link whose [selfId] < remoteId lexicographically is kept and
 * the other is closed. This resolves symmetric simultaneous-dial races without
 * coordination.
 *
 * **Per-link failure:** if a link's remote peer disconnects or errors, that peer
 * is removed from [Seam.peers] and the mesh continues operating. The seam
 * remains [SeamState.Woven] until [Seam.close] is called.
 *
 * **State confinement:** all mutable state is confined to [dispatcher] via
 * [withContext] (the [CompositeSeam] pattern). Production uses
 * `Dispatchers.Default.limitedParallelism(1)`; tests inject a test dispatcher.
 *
 * @param selfId This peer's identity. Sent as the [Hello] preamble on each conn.
 * @param conns Raw [Conn]s to each prospective peer, one per peer. These must be
 *   fresh and unread — [meshSeam] reads the first frame from each as the [Hello]
 *   preamble (channel-backed conns as produced by [us.tractat.kuilt.test.fabric.connPair]
 *   satisfy this requirement).
 * @param dispatcher Confines all mutable mesh state. Never accessed concurrently.
 */
public suspend fun meshSeam(
    selfId: PeerId,
    conns: List<Conn>,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
): Seam {
    // Send Hello on every conn, then await the remote Hello — all concurrently.
    val handshaked: List<Pair<PeerId, Conn>> = coroutineScope {
        conns.map { conn ->
            async {
                conn.send(Hello.encode(selfId))
                val remoteId = Hello.decode(conn.firstFrame())
                remoteId to conn
            }
        }.awaitAll()
    }

    // Dedup: if two conns resolved the same remote id, keep the one where
    // selfId.value < remoteId.value (lower id wins). Close the loser.
    val winners = mutableMapOf<PeerId, Conn>()
    for ((remoteId, conn) in handshaked) {
        val existing = winners[remoteId]
        if (existing == null) {
            winners[remoteId] = conn
        } else {
            // Duplicate link to the same peer — apply dedup.
            if (selfId.value < remoteId.value) {
                // We are the lower id: keep our conn, close the duplicate.
                existing.close()
                winners[remoteId] = conn
            } else {
                // Remote is the lower id: close this conn, keep existing.
                conn.close()
            }
        }
    }

    return MeshSeam(selfId, winners, dispatcher)
}

private class MeshSeam(
    override val selfId: PeerId,
    initialLinks: Map<PeerId, Conn>,
    private val dispatcher: CoroutineContext,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Mutable state: all access via withContext(dispatcher).
    private val links = mutableMapOf<PeerId, Conn>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val inbox = Channel<Swatch>(Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = inbox.receiveAsFlow()

    private var closed = false
    private var seq = 0L

    init {
        // Populate the link map synchronously before any coroutine can observe this object.
        // Safe because no other coroutine has started yet and the constructor is single-threaded.
        initialLinks.forEach { (remoteId, conn) -> links[remoteId] = conn }
        _peers.value = buildPeerSet()

        // Launch a supervised reader for each initial link.
        initialLinks.forEach { (remoteId, conn) ->
            scope.launch { readLoop(remoteId, conn) }
        }
    }

    override suspend fun broadcast(payload: ByteArray): Unit = withContext(dispatcher) {
        check(!closed) { "MeshSeam for $selfId is closed" }
        links.forEach { (remoteId, conn) ->
            runCatchingLink { conn.send(payload) }
                .onFailure { removePeer(remoteId) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = withContext(dispatcher) {
        check(!closed) { "MeshSeam for $selfId is closed" }
        val conn = links[peer] ?: throw PeerNotConnected(peer)
        runCatchingLink { conn.send(payload) }
            .onFailure { removePeer(peer) }
    }

    override suspend fun close(reason: CloseReason) = withContext(dispatcher) {
        if (closed) return@withContext
        closed = true
        _state.value = SeamState.Torn(reason)
        links.values.forEach { conn -> runCatchingLink { conn.close() } }
        links.clear()
        _peers.value = setOf(selfId)
        inbox.close()
        scope.cancel()
    }

    private suspend fun readLoop(remoteId: PeerId, conn: Conn) {
        try {
            conn.incoming.collect { bytes ->
                val swatch = withContext(dispatcher) {
                    if (closed) return@withContext null
                    Swatch(payload = bytes, sender = remoteId, sequence = ++seq)
                }
                if (swatch != null) inbox.trySend(swatch)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Conn errored — treat as remote disconnect.
        } finally {
            withContext(dispatcher) { removePeer(remoteId) }
        }
    }

    /** Remove a peer from the live link map and update the peer set. Must be called on [dispatcher]. */
    private fun removePeer(remoteId: PeerId) {
        if (links.remove(remoteId) != null) {
            _peers.value = buildPeerSet()
        }
    }

    private fun buildPeerSet(): Set<PeerId> = buildSet {
        add(selfId)
        addAll(links.keys)
    }

    /**
     * Run a link operation, catching non-cancellation exceptions.
     * Used for best-effort sends where a dropped link should not tear the seam.
     */
    private inline fun runCatchingLink(block: () -> Unit): Result<Unit> =
        try {
            block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
