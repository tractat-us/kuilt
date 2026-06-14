package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
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
 * @param selfId This peer's identity. Sent as the [Hello] preamble on each conn.
 * @param conns Raw [Conn]s to each prospective peer, one per peer. These must be
 *   fresh and unread — [meshSeam] reads the first frame from each as the [Hello]
 *   preamble (channel-backed conns as produced by [us.tractat.kuilt.test.fabric.connPair]
 *   satisfy this requirement).
 * @param dispatcher The scope for the per-link `readLoop` coroutines (scheduling only — see
 *   the thread-safety note on the returned seam). Production callers pass
 *   `Dispatchers.Default`; test callers pass a dispatcher derived from the test scheduler so
 *   that seam internals share the same virtual clock as the test's `withTimeout`.
 */
public suspend fun meshSeam(
    selfId: PeerId,
    conns: List<Conn>,
    dispatcher: CoroutineContext,
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
    dispatcher: CoroutineContext,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // `links` is shared between `broadcast`/`sendTo` (caller threads) and each `readLoop`'s
    // teardown / per-link send-failure removal (dispatcher threads). It is guarded by `lock`:
    // every read and mutation happens under the lock. Suspending `conn.send`/`conn.close` are
    // NEVER invoked while the lock is held — callers snapshot the target conn(s) under the lock,
    // release, then send/close outside it.
    private val lock = reentrantLock()
    private val links = mutableMapOf<PeerId, Conn>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val inbox = Channel<Swatch>(Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = inbox.receiveAsFlow()

    // Atomic single-shot teardown gate. `close()` and each `readLoop`'s `finally` race to flip it
    // false→true; whoever wins runs the seam-wide teardown, the loser is a no-op — so the seam
    // tears down exactly once regardless of which thread arrives first.
    private val closed = atomic(false)

    // Incremented from MULTIPLE per-link readLoops concurrently — must be atomic.
    private val seq = atomic(0L)

    init {
        // Populate the link map before any readLoop can observe it. No coroutine has started yet.
        links.putAll(initialLinks)
        _peers.value = buildPeerSet()

        // Launch a supervised reader for each initial link.
        initialLinks.forEach { (remoteId, conn) ->
            scope.launch { readLoop(remoteId, conn) }
        }
    }

    private val closedMessage get() = "MeshSeam for $selfId is closed"

    override suspend fun broadcast(payload: ByteArray) {
        check(!closed.value) { closedMessage }
        // Snapshot the live links under the lock, then send OUTSIDE it.
        val targets = lock.withLock { links.toList() }
        targets.forEach { (remoteId, conn) ->
            runCatchingCancellable { conn.send(payload) }
                .onFailure { removePeer(remoteId) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(!closed.value) { closedMessage }
        val conn = lock.withLock { links[peer] } ?: throw PeerNotConnected(peer)
        runCatchingCancellable { conn.send(payload) }
            .onFailure { removePeer(peer) }
    }

    override suspend fun close(reason: CloseReason) {
        // Snapshot the conns to close under the lock; perform the suspending closes outside it.
        val toClose = tearDown(reason) ?: return
        toClose.forEach { conn -> runCatchingCancellable { conn.close() } }
    }

    private suspend fun readLoop(remoteId: PeerId, conn: Conn) {
        try {
            conn.incoming.collect { bytes ->
                if (!closed.value) {
                    inbox.trySend(Swatch(payload = bytes, sender = remoteId, sequence = seq.incrementAndGet()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Conn errored — treat as remote disconnect.
        } finally {
            removePeer(remoteId)
        }
    }

    /**
     * Single-shot seam teardown. Only the first caller (CAS winner) publishes [SeamState.Torn],
     * clears the links, closes the inbox and cancels the scope; it returns the conns to close.
     * Every later caller returns `null`. The suspending `conn.close()` happens OUTSIDE the lock,
     * in the caller. `scope.cancel()` cancels every `readLoop`, whose `finally` calls back into
     * [removePeer] — a no-op once `links` is cleared.
     */
    private fun tearDown(reason: CloseReason): List<Conn>? {
        if (!closed.compareAndSet(expect = false, update = true)) return null
        val conns = lock.withLock {
            val snapshot = links.values.toList()
            links.clear()
            // peers before state: a consumer observing Torn must already see the collapsed roster
            // (matches LinkSeam). Published inside the lock so removePeer cannot overwrite this
            // with a stale partial set after the lock is released.
            _peers.value = setOf(selfId)
            snapshot
        }
        _state.value = SeamState.Torn(reason)
        inbox.close()
        scope.cancel()
        return conns
    }

    /** Remove a single peer from the live link map and update the peer set. Thread-safe. */
    private fun removePeer(remoteId: PeerId) {
        // buildPeerSet and _peers.value assignment are inside the same lock acquisition as the
        // remove so that tearDown's peers-collapse (also inside the lock) cannot be overwritten
        // by a stale buildPeerSet result computed before tearDown cleared links.
        lock.withLock {
            if (links.remove(remoteId) != null) _peers.value = buildPeerSet()
        }
    }

    private fun buildPeerSet(): Set<PeerId> =
        buildSet {
            add(selfId)
            addAll(links.keys)
        }
}
