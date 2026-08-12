@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: the WebSocket variant runs against a real embedded Ktor server — real sockets need a real dispatcher

package us.tractat.kuilt.examples.warp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the WebSocket variant runs against a real embedded Ktor server — real sockets need a real dispatcher
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
import us.tractat.kuilt.warp.BobbinHash
import us.tractat.kuilt.warp.ChicoryWasmRuntime
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.Creel
import us.tractat.kuilt.warp.FedAvg
import us.tractat.kuilt.warp.FedAvgKernelCodec
import us.tractat.kuilt.warp.Op
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.OpRegistry
import us.tractat.kuilt.warp.rosterSnapshot
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.TaskId
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorServerLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
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
 * Several devices each hold private readings of the same hidden trend (`y = 2x + 1`). No device
 * ever shares its readings. Instead the *training step* travels: a tiny WebAssembly kernel,
 * fetched by content address, runs on each device's **own** data and emits only a model update.
 * Those updates merge through a CRDT (FedAvg) on the replicated results board, and every device
 * ends up with the same learned line.
 *
 * **The data really does stay put.** Each device pins its training task **to itself** via
 * [WarpNode.enqueueLocal], so only the data owner runs the step, and it runs on the device's own
 * batch — captured in a node-local op closure ([deviceRegistry]), never serialized. The only thing
 * a task descriptor carries on the wire is the public `(model, lr)` header ([ModelArgs], 24 bytes);
 * the raw `(x, y)` example rows never enter `args` and never cross the fabric. The in-process tier
 * asserts exactly this (see the `descriptor args carry only the model header` assertion), so the
 * "data never moves" claim is proven by the demo, not merely narrated.
 *
 * Run it:  `./gradlew :examples:test --tests "*FederatedLearningExampleTest*"`
 */
class FederatedLearningExampleTest {

    // The FedAvg training kernel, loaded by content address. This is a byte-identical copy of the
    // canonical kernel in kuilt-warp-ml/src/jvmTest/resources (sha256 fa649782…36f8); test
    // resources are not exported across modules, so the demo carries its own copy. The content
    // address is what keeps the two in lockstep — the kernel is loaded by hash, so a drift between
    // the copies would surface immediately as a load/decode mismatch rather than a silent skew.
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

    /**
     * Builds a device's **node-local** op registry: the [kernelOp] closure decodes the public
     * `(model, lr)` from the task's `args`, reads **this device's** [localBatch] (captured here,
     * never serialized), assembles the kernel input locally, and runs the content-addressed kernel
     * loaded once from the device's own [creel] by [hash]. Because the op is pre-registered the
     * node resolves it locally — the private batch never leaves the device.
     */
    private fun deviceRegistry(
        creel: Creel,
        hash: BobbinHash,
        runtime: ChicoryWasmRuntime,
        localBatch: List<Pair<Double, Double>>,
    ): OpRegistry {
        val loadedKernel: Op by lazy {
            runtime.load(checkNotNull(creel.get(hash)) { "kernel bobbin must resolve from local creel" })
        }
        return OpRegistry().apply {
            register(kernelOp, Op { args ->
                val (model, learnRate) = ModelArgs.decode(args)
                loadedKernel.invoke(FedAvgKernelCodec.encodeInput(model, localBatch, learnRate))
            })
        }
    }

    /** Fold a node's converged board for epoch [e] over [activeOwners] into a count-weighted model. */
    private fun fold(node: WarpNode, e: Int, activeOwners: List<String>): List<Double> {
        var m = FedAvg.ZERO
        for (owner in activeOwners) {
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
        // One device per node; each trains on ITS OWN batch via a node-local op. The batch is
        // captured in the op closure, never in a descriptor — only the (model, lr) header travels.
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
                registry = deviceRegistry(creel, hash, runtimes[i], batches.getValue(owners[i])),
                raftNode = checkNotNull(sim.nodes[id]),
                epoch = 0L,
            )
        }
        try {
            sim.settle()
            sim.awaitLeader()

            // The data-locality claim, asserted: a descriptor's args are the (model, lr) header only
            // (24 bytes) — strictly smaller than they would be if the batch were shipped inline.
            val header = ModelArgs.encode(listOf(0.0, 0.0), lr)
            val withBatch = FedAvgKernelCodec.encodeInput(listOf(0.0, 0.0), batches.getValue(owners[0]), lr)
            assertEquals(ModelArgs.SIZE, header.size, "descriptor args carry only the model header — zero example rows")
            assertTrue(header.size < withBatch.size, "shipping the batch would grow args to ${withBatch.size}B; they are ${header.size}B")

            println("Federated learning — 3 devices, private data, true line y = 2x + 1")
            var model = listOf(0.0, 0.0)
            for (e in 0 until 500) {
                // Each device pins its own training task to itself: only the data owner runs it, on
                // its own private batch. args carry only the public (model, lr) header.
                nodes.forEachIndexed { i, node ->
                    node.enqueueLocal(taskId(e, owners[i]), TaskDescriptor(kernelOp, ModelArgs.encode(model, lr)))
                }
                sim.awaitTrue("epoch $e", within = 4.seconds) { nodes.all { n -> owners.all { n.results[taskId(e, it)] != null } } }
                model = fold(nodes[0], e, owners)
                if (e % 100 == 0 || e == 499) println("round %3d  w=[%.3f, %.3f]".format(e, model[0], model[1]))
            }
            println("converged: w=[%.3f, %.3f]  (true: 2.000, 1.000)".format(model[0], model[1]))

            val perNode = nodes.map { fold(it, 499, owners) }
            assertEquals(2.0, model[0], absoluteTolerance = 0.05)
            assertEquals(1.0, model[1], absoluteTolerance = 0.05)
            assertTrue(perNode.all { it == perNode[0] }, "all devices agree bit-for-bit: $perNode")
        } finally {
            nodes.forEach { it.close() }
            runtimes.forEach { it.close() }
        }
    }

    /**
     * The same federated round, now over a **real Ktor WebSocket fabric**: two peers connected
     * by an actual socket (Netty server + OkHttp client). Each peer trains on its **own** private
     * batch via a node-local op and pins the step to itself with [WarpNode.enqueueLocal]; only the
     * `(model, lr)` header and the resulting model updates cross the wire, and the replicated
     * results board converges to the same learned line on both peers.
     *
     * Reader-run only — it binds a real localhost port, so CI skips it. Enable with:
     * `./gradlew :examples:test --tests "*FederatedLearningExampleTest*" -Pwarp.fl.ws=true`
     *
     * This tier runs on the real wall clock (real sockets cannot be driven by a virtual scheduler),
     * so [QuilterConfig.expectVirtualTime] stays at its default `false` and every wait is a hard
     * [withTimeout] bound.
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
        // Real wall clock, real dispatcher — not virtual time, so expectVirtualTime stays false.
        val wsCfg = QuilterConfig(
            antiEntropyInterval = 50.milliseconds,
            fullStateRetryInterval = 100.milliseconds,
        )
        // Two peers, one device each: alice trains on the relay, bob on the client — every device
        // on its own private batch.
        val wsOwners = owners.take(2)

        lateinit var serverLoom: KtorServerLoom
        // Bind 0 and read the port back from the *live* connector. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU:
        // the probe closes before Netty binds, so another process on a loaded box can take the port
        // in that window (`BindException: Address already in use` — #1590). Binding 0 has no window.
        val server = embeddedServer(Netty, port = 0) {
            serverLoom = KtorServerLoom(this, path, selfPeerId = relayId)
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val httpClient = HttpClient(OkHttp) { install(WebSockets) }
        val nodeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val runtimes = List(2) { ChicoryWasmRuntime() }

        try {
            runBlocking {
                val (relaySeam, clientSeam) = coroutineScope {
                    val serverSeam = async { serverLoom.nextLink() }
                    val clientSeam = KtorClientLoom(httpClient = httpClient, selfPeerId = clientId).join(
                        WebSocketAdvertisement(url = "ws://localhost:$port$path", serverPeerId = relayId, sessionName = "fl-client"),
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
                        registry = deviceRegistry(creel, hash, runtimes[i], batches.getValue(wsOwners[i])),
                        epoch = 0L,
                    )
                }
                // Both peers must see each other before either can pin and run its local step.
                withTimeout(10.seconds) {
                    relaySeam.peers.first { clientId in it }
                    clientSeam.peers.first { relayId in it }
                }

                println("Federated learning over a real WebSocket — 2 peers, private data, true line y = 2x + 1")
                val epochs = 500
                var model = listOf(0.0, 0.0)
                for (e in 0 until epochs) {
                    // Each peer pins its own training task to itself and trains on its own batch.
                    nodes.forEachIndexed { i, node ->
                        node.enqueueLocal(taskId(e, wsOwners[i]), TaskDescriptor(kernelOp, ModelArgs.encode(model, lr)))
                    }
                    withTimeout(15.seconds) {
                        while (wsOwners.any { nodes[0].results[taskId(e, it)] == null }) delay(10)
                    }
                    model = fold(nodes[0], e, wsOwners)
                    if (e % 100 == 0 || e == epochs - 1) println("round %3d  w=[%.3f, %.3f]".format(e, model[0], model[1]))
                }
                // Both peers converge the board to the same model over the wire.
                withTimeout(15.seconds) {
                    while (wsOwners.any { nodes[1].results[taskId(epochs - 1, it)] == null }) delay(10)
                }
                val relayModel = fold(nodes[0], epochs - 1, wsOwners)
                val clientModel = fold(nodes[1], epochs - 1, wsOwners)
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

/**
 * The **public** payload that travels in a federated-learning task descriptor: the shared model
 * weights and the learn-rate hyperparameter — and nothing else. Deliberately separate from
 * [FedAvgKernelCodec]'s kernel input (which also carries the private example rows): `args` is
 * `(model, lr)` only, never the batch. Little-endian IEEE-754 f64, matching the kernel ABI.
 *
 * (Mirrors the `ModelArgs` header in kuilt-warp-ml's FedAvgWarpSimTest — the demo keeps its own
 * copy so it stands alone; both encode the identical 24-byte layout.)
 */
private object ModelArgs {

    /** `lr` (f64) + two model weights (f64 each). */
    const val SIZE: Int = 24

    fun encode(model: List<Double>, lr: Double): ByteArray {
        require(model.size == 2) { "v1 model is 2-dimensional, got ${model.size}" }
        val out = ByteArray(SIZE)
        putF64(out, 0, lr)
        putF64(out, 8, model[0])
        putF64(out, 16, model[1])
        return out
    }

    fun decode(bytes: ByteArray): Pair<List<Double>, Double> {
        require(bytes.size == SIZE) { "model args must be $SIZE bytes, got ${bytes.size}" }
        val lr = getF64(bytes, 0)
        return listOf(getF64(bytes, 8), getF64(bytes, 16)) to lr
    }

    private fun putF64(b: ByteArray, o: Int, v: Double) {
        val bits = v.toRawBits()
        for (i in 0 until 8) b[o + i] = (bits ushr (8 * i)).toByte()
    }

    private fun getF64(b: ByteArray, o: Int): Double {
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (b[o + i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }
}
