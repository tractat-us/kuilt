package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F2 end-to-end (Creel + runtime altitude): the training kernel is published once as a
 * content-addressed bobbin; each peer fetches the *same bytes*, runs them on its own private
 * batch via the sandboxed runtime, and the per-peer updates merge through [FedAvg] toward the
 * true line. The data never moves; only the model update does.
 */
class FedAvgFetchAndTrainTest {

    private val kernel: ByteArray = checkNotNull(
        FedAvgFetchAndTrainTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/fedavg_train.wasm",
        ),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    @Test
    fun `peers fetch one kernel, train on private data, and converge`() = runTest {
        val truth = { x: Double -> 2.0 * x + 1.0 }
        val peerBatches = listOf(
            ReplicaId("alice") to listOf(0.0, 1.0, 2.0),
            ReplicaId("bob")   to listOf(3.0, 4.0, 5.0),
            ReplicaId("carol") to listOf(6.0, 7.0, 8.0),
        ).map { (id, xs) -> id to xs.map { it to truth(it) } }

        // Publisher puts the kernel into a Creel; the content address is shared with peers.
        val publisherCreel = Creel()
        val hash = publisherCreel.put(kernel)

        val lr = 0.01
        var model = listOf(0.0, 0.0)

        ChicoryWasmRuntime().use { rt ->
            repeat(500) {
                var merged = FedAvg.ZERO
                for ((peer, batch) in peerBatches) {
                    // Each peer fetches the kernel bytes by content address (code mobility).
                    val fetched = checkNotNull(publisherCreel.get(hash)) { "kernel bobbin must resolve" }
                    val op = rt.load(fetched)
                    val out = op.invoke(FedAvgKernelCodec.encodeInput(model, batch, lr))
                    val update = FedAvgKernelCodec.decodeOutput(out)
                    merged = merged.piece(update.toContribution(peer))
                }
                model = merged.weights
            }
        }

        assertAll(
            { assertEquals(2.0, model[0], absoluteTolerance = 0.05) },   // slope → 2
            { assertEquals(1.0, model[1], absoluteTolerance = 0.05) },   // bias → 1
        )
    }
}
