package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.meshSeam
import kotlin.coroutines.ContinuationInterceptor

/**
 * Build a fully-connected in-memory mesh of [n] peers using [connectionPair] links.
 *
 * For each unordered pair (i, j) one [connectionPair] is created. Peer i gets the
 * first end, peer j gets the second. All [meshSeam] calls run concurrently so
 * the [Hello] preambles cross in parallel (required — serial would deadlock).
 *
 * Returns one [Seam] per peer in index order. All inter-peer handshakes are
 * complete before this function returns.
 */
public suspend fun inMemoryMeshOfSize(n: Int): List<Seam> = coroutineScope {
    val peerIds = (0 until n).map { PeerId("peer-$it") }

    // connectionsByPeer[i] = list of Connections this peer should handshake over.
    val connsByPeer: Array<MutableList<Connection>> = Array(n) { mutableListOf() }

    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val (connI, connJ) = connectionPair()
            connsByPeer[i].add(connI)
            connsByPeer[j].add(connJ)
        }
    }

    // Launch all meshSeam calls concurrently — Hello exchanges must interleave.
    (0 until n).map { i ->
        async { meshSeam(selfId = peerIds[i], connections = connsByPeer[i], dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!) }
    }.awaitAll()
}
