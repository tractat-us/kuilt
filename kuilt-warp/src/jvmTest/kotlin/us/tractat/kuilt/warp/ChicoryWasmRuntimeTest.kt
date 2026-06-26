package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Integration tests for [ChicoryWasmRuntime]: happy-path execution and sandbox load-guards.
 *
 * Happy path: `reverse.wasm` exports the warp linear-memory ABI (`memory`, `warp_alloc`,
 * `warp_run`) and reverses its input bytes.
 *
 * Load-guard tests verify that [ChicoryWasmRuntime.load] rejects capability-violating modules
 * with [WasmLoadException] before any execution occurs.
 */
class ChicoryWasmRuntimeTest {

    private val runtime = ChicoryWasmRuntime()

    private val reverseWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/reverse.wasm",
        ),
    ) { "reverse.wasm not found on classpath" }
        .readBytes()

    private val importsWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/imports.wasm",
        ),
    ) { "imports.wasm not found on classpath" }
        .readBytes()

    private val bigmemWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/bigmem.wasm",
        ),
    ) { "bigmem.wasm not found on classpath" }
        .readBytes()

    private val largeinitWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/largeinit.wasm",
        ),
    ) { "largeinit.wasm not found on classpath" }
        .readBytes()

    // --- Load-guard tests (Task 3) ---

    @Test
    fun loadRejectsModuleWithImports() {
        assertFailsWith<WasmLoadException> { runtime.load(importsWasm) }
    }

    @Test
    fun loadRejectsModuleWithOversizeMemory() {
        assertFailsWith<WasmLoadException> { runtime.load(bigmemWasm) }
    }

    @Test
    fun loadRejectsModuleWithOversizeInitialMemory() {
        assertFailsWith<WasmLoadException> { runtime.load(largeinitWasm) }
    }

    // --- Happy-path tests (Task 2) — guards must not over-reject reverse.wasm ---

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
