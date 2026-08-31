package us.tractat.kuilt.cluster

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.fabric.connectionPair

/**
 * An [InMemoryVoterFabric] that hands out **observable** link ends, so a test can assert on the half of
 * `assembleVoterMesh`'s formation-failure teardown that closes the partially-formed seams.
 *
 * Each [openLink] still produces a plain [connectionPair]; both ends are simply wrapped in a
 * [CloseRecordingConnection] and filed under their [VoterEdge], reachable afterwards via [endsOf].
 * Nothing about the link's behaviour changes — this fabric is not severable and drops no bytes.
 *
 * ## Why a connection-level probe rather than a seam-level one
 *
 * The `hubMesh` seams are created **inside** [assembleVoterMesh] and, on the failure path, are never
 * handed back — the caller gets an exception, not a [VoterMesh], so there is no seam to inspect. What
 * is reachable is the transport underneath: `MeshSeam.close` closes every published link's
 * [Connection], and each `MeshSeam` runs on its own unparented `SupervisorJob` scope, so cancelling the
 * mesh scope alone provably cannot do it. A closed end therefore witnesses the seam close specifically.
 *
 * @param voters the voter ids (passed through to [InMemoryVoterFabric]).
 */
internal class CloseRecordingVoterFabric(voters: List<NodeId>) : InMemoryVoterFabric(voters) {

    private val lock = reentrantLock()
    private val links = mutableMapOf<VoterEdge, Pair<CloseRecordingConnection, CloseRecordingConnection>>()

    /**
     * The `(dialerEnd, acceptorEnd)` recorders for [edge]. Fails if the edge was never dialed — which is
     * itself a useful red: it means formation did not get as far as the test's fixture assumes.
     */
    fun endsOf(edge: VoterEdge): Pair<CloseRecordingConnection, CloseRecordingConnection> =
        lock.withLock { requireNotNull(links[edge]) { "no link was ever opened for $edge; opened: ${links.keys}" } }

    override suspend fun openLink(edge: VoterEdge): Pair<Connection, Connection> {
        val (dialerRaw, acceptorRaw) = connectionPair()
        val recorded = CloseRecordingConnection(dialerRaw) to CloseRecordingConnection(acceptorRaw)
        // connectionPair() does not suspend, so recording inside the lock cannot deadlock; the lock is
        // here because formation dials concurrently (one coroutine per voter) and correctness must not
        // rest on the test dispatcher happening to be single-threaded.
        lock.withLock { links[edge] = recorded }
        return recorded
    }
}

/**
 * One link end that records what was done to it: [closed] completes when someone called [close],
 * [closeCalls] counts **how many times** they did, and [answered] completes on the first frame the
 * *peer* sent back.
 *
 * [answered] is the fixture's precondition probe. A dialed-but-unanswered edge (the stall this suite is
 * built on) also ends up un-closed, so "not closed" alone cannot distinguish *the seam failed to close a
 * live link* from *there was never a live link here*. A frame arriving means the peer completed its side
 * of the `MeshHello` exchange, i.e. this end really was published into a mesh.
 *
 * [closeCalls] exists for the opposite direction: an end that *was* published is the mesh's to close, and
 * a teardown that closes it a second time on its own account has over-reached rather than under-reached.
 * [closed] cannot see that — a [CompletableDeferred] latches on the first completion — so the count is
 * what separates "closed once, by its seam" from "closed again, by a teardown that could not tell a
 * published dial from an abandoned one".
 */
internal class CloseRecordingConnection(private val raw: Connection) : Connection {

    private val lock = reentrantLock()
    private var closeCallCount = 0

    /** Completes when [close] is **called** — the production obligation being pinned. */
    val closed: CompletableDeferred<Unit> = CompletableDeferred()

    /** How many times [close] has been called on this end. */
    val closeCalls: Int get() = lock.withLock { closeCallCount }

    /** Completes on the first inbound frame — i.e. the peer handshaked, so this link went live. */
    val answered: CompletableDeferred<Unit> = CompletableDeferred()

    override val maxFrameBytes: Int? get() = raw.maxFrameBytes

    override val incoming: Flow<ByteArray> get() = raw.incoming.onEach { answered.complete(Unit) }

    override suspend fun send(frame: ByteArray) {
        raw.send(frame)
    }

    /** Records the call **before** delegating: the obligation is that the seam closed us, not that the
     * underlying spool accepted it (`closeBestEffort` swallows a refusal, and so would this). */
    override suspend fun close() {
        lock.withLock { closeCallCount++ }
        closed.complete(Unit)
        raw.close()
    }
}
