@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * F4 — the federated-learning round end-to-end on the substrate: three [WarpNode]s on a
 * [raftSimTest] cluster fetch the F2 training kernel, train on private batches via the
 * free path, and every node converges to one shared model through the replicated results
 * board. The data never moves; only the model update does.
 */
class FedAvgWarpSimTest {

    private val kernel: ByteArray = checkNotNull(
        FedAvgWarpSimTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/fedavg_train.wasm"),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    private val quilterConfig = QuilterConfig(
        antiEntropyInterval = 20.milliseconds,
        fullStateRetryInterval = 30.milliseconds,
        expectVirtualTime = true,
    )

    private val kernelOp = OpId("fedavg-train")
    private val truth = { x: Double -> 2.0 * x + 1.0 }
    private val owners = listOf("alice", "bob", "carol")
    private val batches: Map<String, List<Pair<Double, Double>>> = mapOf(
        "alice" to listOf(0.0, 1.0, 2.0).map { it to truth(it) },
        "bob" to listOf(3.0, 4.0, 5.0).map { it to truth(it) },
        "carol" to listOf(6.0, 7.0, 8.0).map { it to truth(it) },
    )
    private val lr = 0.01

    private fun taskId(epoch: Int, owner: String) = TaskId("e$epoch-$owner")

    /** Fold this node's converged board for [epoch] into a count-weighted FedAvg model. */
    private fun foldGlobalModel(node: WarpNode, epoch: Int): List<Double> {
        val results = node.results
        var merged = FedAvg.ZERO
        for (owner in owners) {
            val res = results[taskId(epoch, owner)] ?: continue
            val update = FedAvgKernelCodec.decodeOutput(res.bytes)
            merged = merged.piece(update.toContribution(ReplicaId(owner), epoch = (epoch + 1).toLong()))
        }
        return if (merged == FedAvg.ZERO) listOf(0.0, 0.0) else merged.weights
    }

    @Test
    fun `peers train on private data and every node converges`() = raftSimTest(n = 3, timeout = 60.seconds) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(
            loom.host(Pattern("warp-fedavg")),
            loom.join(InMemoryTag("wfa-b")),
            loom.join(InMemoryTag("wfa-c")),
        )
        // Each node: empty registry + a Creel preloaded with the kernel, resolved by OpId via lazyFetch.
        val runtimes = sim.nodeIds.map { ChicoryWasmRuntime() }
        val warpNodes = sim.nodeIds.mapIndexed { i, nodeId ->
            val creel = Creel()
            val hash = creel.put(kernel)
            WarpNode(
                selfId = seams[i].selfId,
                seam = seams[i],
                rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = quilterConfig,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry(),
                lazyFetch = WarpLazyFetch(
                    creel = creel,
                    runtime = runtimes[i],
                    opToBobbin = { op -> if (op == kernelOp) hash else null },
                ),
                raftNode = sim.nodes[nodeId]!!,
            )
        }
        try {
            sim.settle()
            sim.awaitLeader()

            val epochs = 500
            var globalModel = listOf(0.0, 0.0)
            for (epoch in 0 until epochs) {
                owners.forEach { owner ->
                    warpNodes[0].enqueue(
                        taskId(epoch, owner),
                        TaskDescriptor(op = kernelOp, args = FedAvgKernelCodec.encodeInput(globalModel, batches.getValue(owner), lr)),
                    )
                }
                sim.awaitTrue("epoch $epoch board converged on all nodes", within = 4.seconds) {
                    warpNodes.all { node -> owners.all { node.results[taskId(epoch, it)] != null } }
                }
                globalModel = foldGlobalModel(warpNodes[0], epoch)
            }

            // Every node folds the same converged board to the same model, → the true line.
            val perNode = warpNodes.map { foldGlobalModel(it, epochs - 1) }
            assertAll(
                { assertEquals(2.0, globalModel[0], absoluteTolerance = 0.05) },
                { assertEquals(1.0, globalModel[1], absoluteTolerance = 0.05) },
                { assertTrue(perNode.all { it == perNode[0] }, "all nodes agree bit-for-bit: $perNode") },
            )
        } finally {
            warpNodes.forEach { it.close() }
            runtimes.forEach { it.close() }
        }
    }

    @Test
    fun `convergence survives leader failover mid-round`() = raftSimTest(n = 3, timeout = 60.seconds) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(
            loom.host(Pattern("warp-fedavg-failover")),
            loom.join(InMemoryTag("wff-b")),
            loom.join(InMemoryTag("wff-c")),
        )
        val runtimes = sim.nodeIds.map { ChicoryWasmRuntime() }
        val warpNodes = sim.nodeIds.mapIndexed { i, nodeId ->
            val creel = Creel()
            val hash = creel.put(kernel)
            WarpNode(
                selfId = seams[i].selfId,
                seam = seams[i],
                rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = quilterConfig,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry(),
                lazyFetch = WarpLazyFetch(
                    creel = creel,
                    runtime = runtimes[i],
                    opToBobbin = { op -> if (op == kernelOp) hash else null },
                ),
                raftNode = sim.nodes[nodeId]!!,
            )
        }
        try {
            sim.settle()
            sim.awaitLeader()
            val leaderId = sim.nodeIds.first { sim.nodes[it]!!.role.value is RaftRole.Leader }

            val epochs = 500
            val preFailover = 20
            var globalModel = listOf(0.0, 0.0)
            suspend fun runEpoch(epoch: Int) {
                owners.forEach { owner ->
                    warpNodes[0].enqueue(
                        taskId(epoch, owner),
                        TaskDescriptor(op = kernelOp, args = FedAvgKernelCodec.encodeInput(globalModel, batches.getValue(owner), lr)),
                    )
                }
                sim.awaitTrue("epoch $epoch converged", within = 4.seconds) {
                    warpNodes.all { node -> owners.all { node.results[taskId(epoch, it)] != null } }
                }
                globalModel = foldGlobalModel(warpNodes[0], epoch)
            }

            // Train normally up to the failover point.
            for (epoch in 0 until preFailover) runEpoch(epoch)

            // Fail the Raft leader mid-training, re-elect among survivors, heal, let the old leader step down.
            val survivors = sim.nodeIds.filter { it != leaderId }.toSet()
            sim.partitionOff(leaderId)
            sim.awaitLeader(among = survivors)
            sim.heal()
            sim.awaitRole(leaderId, RaftRole.Follower)

            // Keep training to convergence; the round survives the failover and every survivor agrees.
            for (epoch in preFailover until epochs) runEpoch(epoch)

            val survivorModels = sim.nodeIds.withIndex()
                .filter { it.value in survivors }
                .map { foldGlobalModel(warpNodes[it.index], epochs - 1) }
            assertAll(
                { assertEquals(2.0, globalModel[0], absoluteTolerance = 0.05) },
                { assertEquals(1.0, globalModel[1], absoluteTolerance = 0.05) },
                { assertTrue(survivorModels.all { it == survivorModels[0] }, "survivors agree: $survivorModels") },
            )
            sim.checkInvariants()
        } finally {
            warpNodes.forEach { it.close() }
            runtimes.forEach { it.close() }
        }
    }
}
