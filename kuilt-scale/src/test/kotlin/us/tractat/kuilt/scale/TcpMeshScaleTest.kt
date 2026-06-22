@file:Suppress("ForbiddenImport") // real-network TCP scaling test — needs real IO dispatcher

package us.tractat.kuilt.scale

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Layer B scaling tests: real localhost TCP mesh, opt-in via -Pscale.tcp.tests=true.
 *
 * Builds a fully-connected TCP mesh for each N in [SWEEP_SIZES], broadcasts one frame
 * from peer 0, then waits for all other peers to receive it. Reports a summary table
 * of wall-clock convergence time and socket counts.
 *
 * Never runs in the default CI gate — guarded by [ScaleTcpTests.assumeEnabled].
 */
class TcpMeshScaleTest {

    @Suppress("ForbiddenMethodCall")
    private val selector = SelectorManager(Dispatchers.IO)

    @BeforeTest
    fun checkEnabled() {
        ScaleTcpTests.assumeEnabled()
    }

    @AfterTest
    fun tearDown() = selector.close()

    @Test
    fun tcpMeshConvergenceSweep() = runBlocking {
        withTimeout(120.seconds) {
            val results = mutableListOf<SweepRow>()

            for (n in SWEEP_SIZES) {
                val row = runSweepForN(n)
                results.add(row)
                println("N=$n  sockets=${row.socketPairs}  broadcasts=${row.broadcasts}  " +
                    "framesIn=${row.framesIn}  wallMs=${row.wallClockMs}")
            }

            printTable(results)

            // Validate Layer A predictions hold over real sockets.
            results.forEach { row ->
                val n = row.n
                // 1 broadcast from peer 0 → N-1 frames received across the cluster
                assertEquals((n - 1).toLong(), row.framesIn,
                    "N=$n: complete mesh 1-broadcast should produce N-1 received frames")
                // Socket pairs for complete graph = N*(N-1)/2
                assertEquals(n * (n - 1) / 2, row.socketPairs,
                    "N=$n: socket pairs should be N*(N-1)/2")
            }
        }
    }

    private suspend fun runSweepForN(n: Int): SweepRow {
        val socketPairs = n * (n - 1) / 2
        val startMs = System.currentTimeMillis()

        val mesh = buildTcpMesh(n, selector)
        val sender = mesh.seams[0]
        val receivers = mesh.seams.drop(1)

        // Collect one incoming frame from each non-sender peer
        coroutineScope {
            val receiverJobs = receivers.map { r ->
                async { r.incoming.take(1).toList() }
            }

            val payload = "scale-probe".encodeToByteArray()
            sender.broadcast(payload)

            receiverJobs.forEach { it.await() }
        }
        val wallClockMs = System.currentTimeMillis() - startMs

        val cluster = mesh.clusterMetrics()
        mesh.close()

        return SweepRow(
            n = n,
            socketPairs = socketPairs,
            broadcasts = cluster.totalBroadcasts,
            framesIn = cluster.totalFramesIn,
            wallClockMs = wallClockMs,
        )
    }

    private fun printTable(results: List<SweepRow>) {
        println("\n=== TCP Mesh Convergence Sweep ===")
        println("%-4s  %-8s  %-12s  %-10s  %-10s".format("N", "Sockets", "Broadcasts", "FramesIn", "WallMs"))
        println("-".repeat(52))
        results.forEach { r ->
            println("%-4d  %-8d  %-12d  %-10d  %-10d".format(
                r.n, r.socketPairs, r.broadcasts, r.framesIn, r.wallClockMs))
        }
        println()
    }

    private data class SweepRow(
        val n: Int,
        val socketPairs: Int,
        val broadcasts: Long,
        val framesIn: Long,
        val wallClockMs: Long,
    )

    companion object {
        private val SWEEP_SIZES = listOf(3, 5, 7, 10)
    }
}
