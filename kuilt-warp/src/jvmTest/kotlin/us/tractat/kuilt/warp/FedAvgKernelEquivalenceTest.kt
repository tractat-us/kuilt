package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class FedAvgKernelEquivalenceTest {

    private val kernel: ByteArray = checkNotNull(
        FedAvgKernelEquivalenceTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/fedavg_train.wasm",
        ),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    @Test
    fun `kernel loads and produces a well-formed update`() = runTest {
        ChicoryWasmRuntime().use { rt ->
            val op = rt.load(kernel)
            val input = FedAvgKernelCodec.encodeInput(
                weights = listOf(1.0, 0.0),
                examples = listOf(1.0 to 2.0, 2.0 to 3.0),
                learnRate = 0.1,
            )
            val update = FedAvgKernelCodec.decodeOutput(op.invoke(input))
            assertAll(
                { assertEquals(2L, update.sampleCount) },
                { assertEquals(2, update.weights.size) },
            )
        }
    }
}
