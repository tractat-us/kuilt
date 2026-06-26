package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Happy-path integration test for [ChicoryWasmRuntime] over the `reverse` kernel.
 *
 * `reverse.wasm` exports the warp linear-memory ABI (`memory`, `warp_alloc`, `warp_run`) and
 * reverses its input bytes. This verifies that [ChicoryWasmRuntime.load] correctly marshals
 * args through shared linear memory and reads results back via the packed i64 pointer/length.
 */
class ChicoryWasmRuntimeTest {

    private val runtime = ChicoryWasmRuntime()

    private val reverseWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/reverse.wasm",
        ),
    ) { "reverse.wasm not found on classpath" }
        .readBytes()

    @Test
    fun fourBytesReverse() = runTest {
        val op = runtime.load(reverseWasm)
        assertContentEquals(
            byteArrayOf(4, 3, 2, 1),
            op.invoke(byteArrayOf(1, 2, 3, 4)),
        )
    }

    @Test
    fun emptyInputReturnsEmpty() = runTest {
        val op = runtime.load(reverseWasm)
        assertContentEquals(byteArrayOf(), op.invoke(byteArrayOf()))
    }

    @Test
    fun asciiPayloadRoundTripsReversed() = runTest {
        val op = runtime.load(reverseWasm)
        val input = "Hello, warp!".encodeToByteArray()
        val expected = input.reversedArray()
        val first = op.invoke(input)
        val second = op.invoke(input)
        assertAll(
            { assertContentEquals(expected, first) },
            { assertContentEquals(expected, second, "second invoke reuses the same instance") },
        )
    }
}
