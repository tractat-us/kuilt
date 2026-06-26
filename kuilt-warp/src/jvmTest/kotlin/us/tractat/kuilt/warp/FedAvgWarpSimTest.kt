@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * F4 — the federated-learning round end-to-end on the substrate, **genuinely data-local**.
 *
 * Three [WarpNode]s ride a [raftSimTest] cluster, one per simulated *device*. Each device holds
 * **only its own private batch** of training examples. Every epoch each device pins a training
 * task **to itself** via [WarpNode.enqueueLocal], so a device runs the step on **its own data and
 * no one else's**. The two things that legitimately travel are public:
 *
 * - the **training step** — a content-addressed wasm kernel, loaded by content hash from the
 *   device's local [Creel] *inside* the device's node-local op (code mobility), and
 * - the **shared model** — the `(globalModel, lr)` pair carried in the task descriptor's `args`.
 *
 * The raw `(x, y)` training examples are **never serialized into a descriptor**. Each device's
 * batch is captured in its own node-local [Op] closure (see [deviceRegistry]) and supplied to the
 * kernel locally; it never enters `args` and never crosses the fabric. The replicated results
 * board carries only each device's [TrainingUpdate] (sample count + weight vector), and every
 * device folds the same converged board to the same model through [FedAvg].
 *
 * The locality claim is asserted, not just narrated: see `assertLocality` — the descriptor `args`
 * are header-only (`model` + `lr`), strictly smaller than they would be if the batch were shipped.
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

    /**
     * Builds a device's **node-local** op registry. The returned registry maps [kernelOp] to a
     * closure that:
     * 1. decodes the public `(globalModel, lr)` from the task's `args`,
     * 2. reads **this device's** [localBatch] — captured here, never serialized into a descriptor,
     * 3. assembles the kernel input `encodeInput(globalModel, localBatch, lr)` **locally**, and
     * 4. runs the content-addressed kernel, loaded once (lazily, then cached) from the device's own
     *    [creel] by content [hash] via its [runtime].
     *
     * Because the op is pre-registered, [WarpNode] resolves it locally and never touches the
     * lazy-fetch path; the kernel's mobility lives *inside* the op as a content-addressed load.
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
                val (globalModel, learnRate) = ModelArgs.decode(args)
                loadedKernel.invoke(FedAvgKernelCodec.encodeInput(globalModel, localBatch, learnRate))
            })
        }
    }

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

    /**
     * Proves the descriptor `args` carry only the public `(model, lr)` and **never** the batch:
     * they are exactly [ModelArgs.SIZE] bytes and strictly smaller than the kernel input that
     * *would* result from shipping a device's example rows. Under the old data-shipping design the
     * args were `encodeInput(model, batch, lr)` and this assertion would fail.
     */
    private fun assertLocality(model: List<Double>) {
        val shipped = ModelArgs.encode(model, lr)
        val withBatch = FedAvgKernelCodec.encodeInput(model, batches.getValue(owners.first()), lr)
        assertAll(
            { assertEquals(ModelArgs.SIZE, shipped.size, "descriptor args are (model, lr) only — zero example rows") },
            {
                assertTrue(
                    shipped.size < withBatch.size,
                    "shipping the batch would grow args to ${withBatch.size}B; they are ${shipped.size}B — data stays on-device",
                )
            },
        )
    }

    @Test
    fun `peers train on their own private data and every device converges`() =
        raftSimTest(n = 3, timeout = 60.seconds) { sim ->
            val loom = InMemoryLoom()
            val seams = listOf(
                loom.host(Pattern("warp-fedavg")),
                loom.join(InMemoryTag("wfa-b")),
                loom.join(InMemoryTag("wfa-c")),
            )
            // One device per node. Each gets a Creel preloaded with the kernel and a node-local op
            // that trains on THIS device's batch only — the batch is captured in the op, not in args.
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
                    registry = deviceRegistry(creel, hash, runtimes[i], batches.getValue(owners[i])),
                    raftNode = sim.nodes[nodeId]!!,
                )
            }
            try {
                sim.settle()
                sim.awaitLeader()

                assertLocality(listOf(0.0, 0.0))

                val epochs = 500
                var globalModel = listOf(0.0, 0.0)
                for (epoch in 0 until epochs) {
                    // EACH device pins its own training task to itself: only the data owner runs it,
                    // on its own private batch. args carry only the public (model, lr).
                    warpNodes.forEachIndexed { i, node ->
                        node.enqueueLocal(
                            taskId(epoch, owners[i]),
                            TaskDescriptor(op = kernelOp, args = ModelArgs.encode(globalModel, lr)),
                        )
                    }
                    sim.awaitTrue("epoch $epoch board converged on all nodes", within = 4.seconds) {
                        warpNodes.all { node -> owners.all { node.results[taskId(epoch, it)] != null } }
                    }
                    // Every device folds the same replicated board to the same next-epoch model.
                    globalModel = foldGlobalModel(warpNodes[0], epoch)
                }

                val perNode = warpNodes.map { foldGlobalModel(it, epochs - 1) }
                assertAll(
                    { assertEquals(2.0, globalModel[0], absoluteTolerance = 0.05) },
                    { assertEquals(1.0, globalModel[1], absoluteTolerance = 0.05) },
                    { assertTrue(perNode.all { it == perNode[0] }, "all devices agree bit-for-bit: $perNode") },
                )
            } finally {
                warpNodes.forEach { it.close() }
                runtimes.forEach { it.close() }
            }
        }
}

/**
 * The **public** payload that travels in a federated-learning task descriptor: the shared model
 * weights and the learn-rate hyperparameter — and nothing else. Deliberately separate from
 * [FedAvgKernelCodec]'s kernel input (which also carries the private example rows): a reviewer can
 * see at a glance that `args` is `(model, lr)` only, never the batch. Little-endian IEEE-754 f64,
 * matching the kernel ABI's byte-determinism.
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
