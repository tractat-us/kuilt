@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.examples.warp

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
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
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
