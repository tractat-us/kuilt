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
    fun `kernel output equals the reference trainer bit-for-bit across inputs`() = runTest {
        val cases = listOf(
            Triple(listOf(1.0, 0.0), listOf(1.0 to 2.0, 2.0 to 3.0), 0.1),
            Triple(listOf(0.0, 0.0), listOf(0.0 to 1.0, 1.0 to 3.0, 2.0 to 5.0), 0.05),
            Triple(listOf(-1.5, 2.0), listOf(3.0 to 7.0, 4.0 to 9.0, 5.0 to 11.0, 6.0 to 13.0), 0.001),
        )
        ChicoryWasmRuntime().use { rt ->
            val op = rt.load(kernel)
            for ((w, ex, lr) in cases) {
                val kernelOut = FedAvgKernelCodec.decodeOutput(op.invoke(FedAvgKernelCodec.encodeInput(w, ex, lr)))
                val ref = ReferenceTrainer.step(w, ex, lr)
                assertAll(
                    { assertEquals(ex.size.toLong(), kernelOut.sampleCount) },
                    // bit-for-bit: same IEEE-754 double, same operation order
                    { assertEquals(ref[0].toRawBits(), kernelOut.weights[0].toRawBits(), "w0' bits for $w/$lr") },
                    { assertEquals(ref[1].toRawBits(), kernelOut.weights[1].toRawBits(), "w1' bits for $w/$lr") },
                )
            }
        }
    }

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
