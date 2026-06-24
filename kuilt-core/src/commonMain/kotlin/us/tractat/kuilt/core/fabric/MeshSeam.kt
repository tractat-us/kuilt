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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * A [Seam] over a fully-connected N-peer mesh of point-to-point [Connection]s, with support for
 * admitting links that arrive after construction.
 *
 * Obtain one from [meshSeam]. Beyond the [Seam] contract it adds [addLink] for dynamic peer-join.
 */
public interface Mesh : Seam {
    /**
     * Admit a [Connection] to a peer that dialed in after construction.
     *
     * Exchanges the mesh preamble to learn the remote [PeerId], dedups against existing links
     * using the same canonical rule as construction (see [meshSeam]), updates [peers], and
     * launches the link's read loop. Suspends until the preamble exchange completes.
     *
     * @param conn A fresh, unread [Connection]. The mesh wraps it with [singleCollection] before reading,
     *   so the preamble read and the read loop share ONE collection of [Connection.incoming] — a cold,
     *   single-collection conn (a stream fabric's `framed()`) works as well as a hot channel-backed
     *   one, exactly as construction does.
     */
    public suspend fun addLink(conn: Connection)
}

/**
 * The mesh handshake preamble: this peer's [PeerId] plus a per-connection [nonce].
 *
 * Distinct from [Hello] (which carries only the id) because mesh dedup needs a value that BOTH
 * ends of the same physical link share. Each side draws a random nonce; the canonical link
 * identity is a pure, order-independent function of the two nonces, so both ends derive the same
 * survivor when a duplicate link to the same peer exists — cross-node dedup agreement with no
 * coordination.
 *
 * Wire format: length-prefix — `[4-byte big-endian id length][id UTF-8 bytes][NONCE_BYTES nonce bytes]`.
 * No delimiter: the id length field makes the frame self-describing. The nonce is raw bytes (not
 * hex-encoded) and always exactly [NONCE_BYTES] bytes long.
 */
internal data class MeshHello(val peerId: PeerId, val nonce: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MeshHello && peerId == other.peerId && nonce.contentEquals(other.nonce))

    override fun hashCode(): Int = 31 * peerId.hashCode() + nonce.toList().hashCode()

    public companion object {
        public fun encode(peerId: PeerId, nonce: ByteArray): ByteArray {
            val idBytes = peerId.value.encodeToByteArray()
            return ByteArray(4 + idBytes.size + nonce.size).also { buf ->
                buf.writeInt(idBytes.size, offset = 0)
                idBytes.copyInto(buf, destinationOffset = 4)
                nonce.copyInto(buf, destinationOffset = 4 + idBytes.size)
            }
        }

        public fun decode(frame: ByteArray): MeshHello {
            val idLen = frame.readInt(offset = 0)
            val peerId = PeerId(frame.decodeToString(startIndex = 4, endIndex = 4 + idLen))
            val nonce = frame.copyOfRange(4 + idLen, frame.size)
            return MeshHello(peerId, nonce)
        }
    }
}

private const val NONCE_BYTES = 16

/** Write [value] as a 4-byte big-endian integer into [this] at [offset]. */
private fun ByteArray.writeInt(value: Int, offset: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

/** Read a 4-byte big-endian integer from [this] at [offset]. */
private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)

/** Hex-encode [this] for use in the canonical link-nonce comparison string. */
private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** A handshaked link: the remote peer, its conn, and the canonical link nonce both ends agree on. */
private class Link(val remoteId: PeerId, val conn: Connection, val linkNonce: String)

/**
 * Build a fully-connected N-peer mesh [Seam] from a set of raw point-to-point connections.
 *
 * For each [Connection] in [connections], [meshSeam] exchanges a [MeshHello] preamble (this peer's id plus a
 * random per-connection nonce) to learn the remote [PeerId]. Both sides of a link exchange their
 * preamble concurrently, so all exchanges in [connections] run in parallel — this function suspends
 * until every handshake completes.
 *
 * **Dedup (cross-node agreement):** if two connections resolve the same remote id (duplicate links from
 * a simultaneous dial), both ends keep the link with the lexicographically smallest *link nonce* —
 * a canonical, order-independent function of the two per-connection nonces. Because both ends see
 * both nonces, they derive the same survivor and close the same loser, with no coordination. The
 * old self-relative `selfId < remoteId` rule could leave a link half-open (the two ends disagreed
 * on the survivor); the nonce-based rule cannot.
 *
 * **Per-link failure:** if a link's remote peer disconnects or errors, that peer is removed from
 * [Seam.peers] and the mesh continues operating. The seam remains [SeamState.Woven] until
 * [Seam.close] is called.
 *
 * **Dynamic join:** admit a link that arrives later via [Mesh.addLink].
 *
 * @param selfId This peer's identity. Sent in the [MeshHello] preamble on each conn.
 * @param connections Raw [Connection]s to each prospective peer. These must be fresh and unread. Each is
 *   wrapped with [singleCollection] before reading, so the preamble read and the per-link read
 *   loop share ONE collection of [Connection.incoming] — a cold, single-collection connection (a stream
 *   fabric's `framed()`) works as well as a hot channel-backed one
 *   ([us.tractat.kuilt.test.fabric.connectionPair]).
 * @param dispatcher The scope for the per-link `readLoop` coroutines (scheduling only — see the
 *   thread-safety note on the returned seam). Production callers pass `Dispatchers.Default`; test
 *   callers pass a dispatcher derived from the test scheduler so seam internals share the same
 *   virtual clock as the test's `withTimeout`.
 * @param random Source of per-connection nonces. Production defaults to [Random.Default]; tests
 *   pass a seeded [Random] so the dedup tiebreak is deterministic.
 * @param policy Delivery policy for the seam's inbound [Spool]. Defaults to [DeliveryPolicy.Reliable]
 *   (bounded, backpressured). Pass [DeliveryPolicy.Lossy] for a lossy radio-style fabric or
 *   [DeliveryPolicy.Strict] in tests that assert no overflow.
 */
public suspend fun meshSeam(
    selfId: PeerId,
    connections: List<Connection>,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Mesh {
    val links = coroutineScope {
        connections.map { conn -> async { handshakeLink(selfId, conn, dispatcher, random) } }.awaitAll()
    }

    // Dedup duplicate links to the same peer, keeping the canonical survivor on every node.
    val winners = mutableMapOf<PeerId, Link>()
    val losers = mutableListOf<Link>()
    for (link in links) {
        val existing = winners[link.remoteId]
        when {
            existing == null -> winners[link.remoteId] = link
            link.linkNonce < existing.linkNonce -> { losers += existing; winners[link.remoteId] = link }
            else -> losers += link
        }
    }
    losers.forEach { runCatchingCancellable { it.conn.close() } }

    return MeshSeam(selfId, winners, dispatcher, random, policy)
}

/**
 * Exchange the mesh preamble on [conn] and return the resulting [Link] with its canonical nonce.
 *
 * The conn is wrapped with [singleCollection] before the preamble read, so the read loop launched
 * later collects the SAME single upstream collection — cold, single-collection connections (a stream
 * fabric's `framed()`) work, not just hot channel-backed ones. The wrapper conn is what the [Link]
 * carries, so dedup/teardown closes and the read loop all operate on it.
 */
private suspend fun handshakeLink(selfId: PeerId, conn: Connection, dispatcher: CoroutineContext, random: Random): Link {
    val single = conn.singleCollection(dispatcher)
    val myNonce = random.nextBytes(NONCE_BYTES)
    single.send(MeshHello.encode(selfId, myNonce))
    val remote = MeshHello.decode(single.firstFrame())
    return Link(remote.peerId, single, canonicalLinkNonce(myNonce, remote.nonce))
}

/** Order-independent link identity from the two endpoint nonces — identical on both ends. */
private fun canonicalLinkNonce(a: ByteArray, b: ByteArray): String {
    val (lo, hi) = listOf(a.toHex(), b.toHex()).sorted()
    return "$lo:$hi"
}

private class MeshSeam(
    override val selfId: PeerId,
    initialLinks: Map<PeerId, Link>,
    private val dispatcher: CoroutineContext,
    private val random: Random,
    policy: DeliveryPolicy,
) : Mesh {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // `links` is shared between `broadcast`/`sendTo` (caller threads) and each `readLoop`'s
    // teardown / per-link send-failure removal (dispatcher threads), and `addLink`. It is guarded
    // by `lock`: every read and mutation happens under the lock. Suspending `conn.send`/`conn.close`
    // are NEVER invoked while the lock is held — callers snapshot the target conn(s) under the lock,
    // release, then send/close outside it.
    private val lock = reentrantLock()
    private val links = mutableMapOf<PeerId, Link>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val spool = Spool(policy)
    override val incoming: Flow<Swatch> = spool.incoming

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
        initialLinks.values.forEach { link -> scope.launch { readLoop(link.remoteId, link.conn) } }
    }

    private val closedMessage get() = "MeshSeam for $selfId is closed"

    override suspend fun addLink(conn: Connection) {
        check(!closed.value) { closedMessage }
        val link = handshakeLink(selfId, conn, dispatcher, random)
        // Dedup against any existing link to the same peer using the canonical nonce, then publish.
        // Snapshot the loser under the lock, close it outside.
        val loser = admitOrReject(link)
        loser?.let { runCatchingCancellable { it.close() } }
        if (loser != link.conn) scope.launch { readLoop(link.remoteId, link.conn) }
    }

    /**
     * Install [link] under the lock if it wins dedup, returning the conn to close (the loser) — the
     * incoming conn itself if it lost, the displaced existing conn if it won, or `null` if it is the
     * first link to that peer. Suspending closes happen in the caller, outside the lock.
     */
    private fun admitOrReject(link: Link): Connection? = lock.withLock {
        if (closed.value) return@withLock link.conn
        val existing = links[link.remoteId]
        when {
            existing == null -> { links[link.remoteId] = link; _peers.value = buildPeerSet(); null }
            link.linkNonce < existing.linkNonce -> { links[link.remoteId] = link; existing.conn }
            else -> link.conn
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(!closed.value) { closedMessage }
        // Snapshot the live links under the lock, then send OUTSIDE it.
        val targets = lock.withLock { links.values.map { it.remoteId to it.conn } }
        targets.forEach { (remoteId, conn) ->
            runCatchingCancellable { conn.send(payload) }
                .onFailure { removePeer(remoteId) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(!closed.value) { closedMessage }
        val conn = lock.withLock { links[peer]?.conn } ?: throw PeerNotConnected(peer)
        runCatchingCancellable { conn.send(payload) }
            .onFailure { removePeer(peer) }
    }

    override suspend fun close(reason: CloseReason) {
        // Snapshot the connections to close under the lock; perform the suspending closes outside it.
        val toClose = tearDown(reason) ?: return
        toClose.forEach { conn -> runCatchingCancellable { conn.close() } }
    }

    private suspend fun readLoop(remoteId: PeerId, conn: Connection) {
        try {
            conn.incoming.collect { bytes ->
                if (!closed.value) {
                    // Sequence number is assigned atomically outside the lock. `deliver` SUSPENDS
                    // for backpressure (SUSPEND policy) — never called while holding `lock`.
                    spool.deliver(Swatch(payload = bytes, sender = remoteId, sequence = seq.incrementAndGet()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Connection errored — treat as remote disconnect.
        } finally {
            removePeer(remoteId, conn)
        }
    }

    /**
     * Single-shot seam teardown. Only the first caller (CAS winner) publishes [SeamState.Torn],
     * clears the links, closes the inbox and cancels the scope; it returns the connections to close.
     * Every later caller returns `null`. The suspending `conn.close()` happens OUTSIDE the lock,
     * in the caller. `scope.cancel()` cancels every `readLoop`, whose `finally` calls back into
     * [removePeer] — a no-op once `links` is cleared.
     */
    private fun tearDown(reason: CloseReason): List<Connection>? {
        if (!closed.compareAndSet(expect = false, update = true)) return null
        val conns = lock.withLock {
            val snapshot = links.values.map { it.conn }
            links.clear()
            // peers before state: a consumer observing Torn must already see the collapsed roster
            // (matches LinkSeam). Published inside the lock so removePeer cannot overwrite this
            // with a stale partial set after the lock is released.
            _peers.value = setOf(selfId)
            snapshot
        }
        _state.value = SeamState.Torn(reason)
        spool.close()
        scope.cancel()
        return conns
    }

    /**
     * Remove a peer from the live link map and update the peer set. Thread-safe.
     *
     * When [conn] is given, only remove the peer if the live link is THAT conn — so a stale
     * readLoop for a deduped/replaced link can't evict the surviving link to the same peer.
     */
    private fun removePeer(remoteId: PeerId, conn: Connection? = null) {
        // buildPeerSet and _peers.value assignment are inside the same lock acquisition as the
        // remove so that tearDown's peers-collapse (also inside the lock) cannot be overwritten
        // by a stale buildPeerSet result computed before tearDown cleared links.
        lock.withLock {
            val live = links[remoteId] ?: return@withLock
            if (conn != null && live.conn !== conn) return@withLock
            links.remove(remoteId)
            _peers.value = buildPeerSet()
        }
    }

    private fun buildPeerSet(): Set<PeerId> =
        buildSet {
            add(selfId)
            addAll(links.keys)
        }
}
