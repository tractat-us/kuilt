@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.examples.warp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.warp.ChicoryWasmRuntime
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.Creel
import us.tractat.kuilt.warp.FedAvg
import us.tractat.kuilt.warp.FedAvgKernelCodec
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.OpRegistry
import us.tractat.kuilt.warp.rosterSnapshot
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.TaskId
import us.tractat.kuilt.warp.WarpLazyFetch
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorServerLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Federated learning on kuilt — the payoff demo.
 *
 * Three devices each hold private readings of the same hidden trend (`y = 2x + 1`). No device
 * ever shares its readings. Instead the *training step* travels: a tiny WebAssembly kernel,
 * fetched by content address, runs on each device's own data and emits only a model update.
 * Those updates merge through a CRDT (FedAvg) on the replicated results board, and every device
 * ends up with the same learned line.
 *
 * Run it:  `./gradlew :examples:test --tests "*FederatedLearningExampleTest*"`
 */
class FederatedLearningExampleTest {

    private val kernel: ByteArray = checkNotNull(
        FederatedLearningExampleTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/fedavg_train.wasm"),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    private val cfg = QuilterConfig(
        antiEntropyInterval = 20.milliseconds,
        fullStateRetryInterval = 30.milliseconds,
        expectVirtualTime = true,
    )
    private val kernelOp = OpId("fedavg-train")
    private val owners = listOf("alice", "bob", "carol")
    private val batches: Map<String, List<Pair<Double, Double>>> = mapOf(
        "alice" to listOf(0.0, 1.0, 2.0),
        "bob" to listOf(3.0, 4.0, 5.0),
        "carol" to listOf(6.0, 7.0, 8.0),
    ).mapValues { (_, xs) -> xs.map { it to (2.0 * it + 1.0) } }
    private val lr = 0.01

    private fun taskId(e: Int, owner: String) = TaskId("e$e-$owner")

    private fun fold(node: WarpNode, e: Int): List<Double> {
        var m = FedAvg.ZERO
        for (owner in owners) {
            val r = node.results[taskId(e, owner)] ?: continue
            m = m.piece(FedAvgKernelCodec.decodeOutput(r.bytes).toContribution(ReplicaId(owner), epoch = (e + 1).toLong()))
        }
        return if (m == FedAvg.ZERO) listOf(0.0, 0.0) else m.weights
    }

    @Test
    fun `federated learning converges (in-process)`() = raftSimTest(n = 3, timeout = 60.seconds) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(loom.host(Pattern("fl-demo")), loom.join(InMemoryTag("fl-b")), loom.join(InMemoryTag("fl-c")))
        val runtimes = sim.nodeIds.map { ChicoryWasmRuntime() }
        val nodes = sim.nodeIds.mapIndexed { i, id ->
            val creel = Creel()
            val hash = creel.put(kernel)
            WarpNode(
                selfId = seams[i].selfId,
                seam = seams[i],
                rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = cfg,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry(),
                lazyFetch = WarpLazyFetch(creel, runtimes[i]) { op -> if (op == kernelOp) hash else null },
                raftNode = sim.nodes[id]!!,
            )
        }
        try {
            sim.settle()
            sim.awaitLeader()

            println("Federated learning — 3 devices, private data, true line y = 2x + 1")
            var model = listOf(0.0, 0.0)
            for (e in 0 until 500) {
                owners.forEach { owner ->
                    nodes[0].enqueue(taskId(e, owner), TaskDescriptor(kernelOp, FedAvgKernelCodec.encodeInput(model, batches.getValue(owner), lr)))
                }
                sim.awaitTrue("epoch $e", within = 4.seconds) { nodes.all { n -> owners.all { n.results[taskId(e, it)] != null } } }
                model = fold(nodes[0], e)
                if (e % 100 == 0 || e == 499) println("round %3d  w=[%.3f, %.3f]".format(e, model[0], model[1]))
            }
            println("converged: w=[%.3f, %.3f]  (true: 2.000, 1.000)".format(model[0], model[1]))

            assertEquals(2.0, model[0], absoluteTolerance = 0.05)
            assertEquals(1.0, model[1], absoluteTolerance = 0.05)
        } finally {
            nodes.forEach { it.close() }
            runtimes.forEach { it.close() }
        }
    }

    /**
     * The same federated round, now over a **real Ktor WebSocket fabric**: two peers connected
     * by an actual socket (Netty server + OkHttp client). The training kernel is fetched by
     * content address on each peer, the model updates cross the wire, and the replicated results
     * board converges to the same learned line.
     *
     * Reader-run only — it binds a real localhost port, so CI skips it. Enable with:
     * `./gradlew :examples:test --tests "*FederatedLearningExampleTest*" -Pwarp.fl.ws=true`
     *
     * Unlike the in-process tier this runs on the real wall clock (real sockets cannot be driven
     * by a virtual scheduler), so every wait is a hard [withTimeout] bound.
     */
    @Test
    fun `federated learning converges over a real WebSocket fabric`() {
        Assumptions.assumeTrue(
            System.getProperty("warp.fl.ws") == "true",
            "WS demo is reader-run; pass -Pwarp.fl.ws=true to run it",
        )

        val relayId = PeerId("fl-relay")
        val clientId = PeerId("fl-client")
        val path = "/ws/fl-demo"
        val port = ServerSocket(0).use { it.localPort }
        val wsCfg = QuilterConfig(
            antiEntropyInterval = 50.milliseconds,
            fullStateRetryInterval = 100.milliseconds,
            expectVirtualTime = true,
        )

        lateinit var serverLoom: KtorServerLoom
        val server = embeddedServer(Netty, port = port) {
            serverLoom = KtorServerLoom(this, path, selfPeerId = relayId)
        }
        server.start(wait = false)
        val httpClient = HttpClient(OkHttp) { install(WebSockets) }
        val nodeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val runtimes = List(2) { ChicoryWasmRuntime() }

        try {
            runBlocking {
                val (relaySeam, clientSeam) = coroutineScope {
                    val serverSeam = async { serverLoom.nextLink() }
                    val clientSeam = KtorClientLoom(httpClient = httpClient, selfPeerId = clientId).join(
                        WebSocketAdvertisement(url = "ws://localhost:$port$path", serverPeerId = relayId, displayName = "fl-client"),
                    )
                    serverSeam.await() to clientSeam
                }
                val seams: List<Seam> = listOf(relaySeam, clientSeam)
                val nodes = seams.mapIndexed { i, seam ->
                    val creel = Creel()
                    val hash = creel.put(kernel)
                    WarpNode(
                        selfId = seam.selfId,
                        seam = seam,
                        rosterFlow = seam.rosterSnapshot(),
                        scope = nodeScope,
                        quilterConfig = wsCfg,
                        clock = { Clock.System.now() },
                        strategy = ClaimStrategy.Ring,
                        registry = OpRegistry(),
                        lazyFetch = WarpLazyFetch(creel, runtimes[i]) { op -> if (op == kernelOp) hash else null },
                    )
                }
                // Both peers must see each other before the ring can assign tasks.
                withTimeout(10.seconds) {
                    relaySeam.peers.first { clientId in it }
                    clientSeam.peers.first { relayId in it }
                }

                println("Federated learning over a real WebSocket — 2 peers, private data, true line y = 2x + 1")
                val epochs = 500
                var model = listOf(0.0, 0.0)
                for (e in 0 until epochs) {
                    owners.forEach { owner ->
                        nodes[0].enqueue(taskId(e, owner), TaskDescriptor(kernelOp, FedAvgKernelCodec.encodeInput(model, batches.getValue(owner), lr)))
                    }
                    withTimeout(15.seconds) {
                        while (owners.any { nodes[0].results[taskId(e, it)] == null }) delay(10)
                    }
                    model = fold(nodes[0], e)
                    if (e % 100 == 0 || e == epochs - 1) println("round %3d  w=[%.3f, %.3f]".format(e, model[0], model[1]))
                }
                // Both peers converge the board to the same model over the wire.
                withTimeout(15.seconds) {
                    while (owners.any { nodes[1].results[taskId(epochs - 1, it)] == null }) delay(10)
                }
                val relayModel = fold(nodes[0], epochs - 1)
                val clientModel = fold(nodes[1], epochs - 1)
                println("converged: relay=%s client=%s (true: 2.000, 1.000)".format(relayModel, clientModel))

                assertEquals(2.0, relayModel[0], absoluteTolerance = 0.05)
                assertEquals(1.0, relayModel[1], absoluteTolerance = 0.05)
                assertTrue(relayModel == clientModel, "both peers agree over the wire: relay=$relayModel client=$clientModel")

                nodes.forEach { it.close() }
            }
        } finally {
            runtimes.forEach { it.close() }
            nodeScope.cancel()
            httpClient.close()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
        }
    }
}
