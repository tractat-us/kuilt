package us.tractat.kuilt.scale

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor

/**
 * The result of building an in-memory mesh: one [MeteredSeam] per peer plus helpers
 * for reading cluster-wide metrics and closing the whole mesh.
 *
 * @property seams One [MeteredSeam] per peer, in peer-index order.
 */
public class InMemoryMesh(public val seams: List<MeteredSeam>) {

    /** Number of peers in this mesh. */
    public val size: Int get() = seams.size

    /** Current cluster-wide metrics snapshot (sum over all peers). */
    public fun clusterMetrics(): ClusterMetrics = seams.map { it.snapshot() }.sum()

    /** Per-peer metrics snapshots in peer-index order. */
    public fun peerMetrics(): List<SeamMetrics> = seams.map { it.snapshot() }

    /** Close every seam. */
    public suspend fun close() { seams.forEach { it.close() } }
}

/**
 * Build an in-memory mesh of [n] peers wired according to [topology], wrapping each
 * resulting [Seam] in a [MeteredSeam] for message-count instrumentation.
 *
 * All [meshSeam] handshakes run concurrently (required — serial would deadlock since
 * each side suspends waiting for the peer's preamble). The function suspends until
 * every handshake in [topology]'s edge set completes.
 *
 * @param n Number of peers (must be ≥ 2 for [Topology.Complete] and [Topology.Ring];
 *   ≥ 2 for [Topology.Star]).
 * @param topology Which peer-pairs to wire. Defaults to [Topology.Complete].
 */
public suspend fun buildInMemoryMesh(
    n: Int,
    topology: Topology = Topology.Complete,
): InMemoryMesh = coroutineScope {
    require(n >= 2) { "Mesh requires at least 2 peers, got $n" }

    val peerIds = (0 until n).map { PeerId("peer-$it") }
    val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!

    // Map each peer index to its list of connections for meshSeam.
    val connsByPeer: Array<MutableList<us.tractat.kuilt.core.fabric.Connection>> =
        Array(n) { mutableListOf() }

    for ((i, j) in topology.edges(n)) {
        val (connI, connJ) = connectionPair()
        connsByPeer[i].add(connI)
        connsByPeer[j].add(connJ)
    }

    // Launch all meshSeam calls concurrently — Hello preambles must interleave.
    val rawSeams: List<Seam> = (0 until n).map { i ->
        async {
            meshSeam(
                selfId = peerIds[i],
                connections = connsByPeer[i],
                dispatcher = dispatcher,
            )
        }
    }.awaitAll()

    val meteredSeams = rawSeams.map { MeteredSeam(it) }
    InMemoryMesh(meteredSeams)
}
