package us.tractat.kuilt.warp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [ChicoryWasmRuntime]: happy-path execution plus load- and run-guards.
 *
 * Happy path: `reverse.wasm` exports the warp linear-memory ABI (`memory`, `warp_alloc`,
 * `warp_run`) and reverses its input bytes.
 *
 * Load-guard tests verify that [ChicoryWasmRuntime.load] rejects capability-violating modules
 * with [WasmLoadException] before any execution occurs.
 *
 * Run-guard tests verify that a runaway (`loop.wasm`) or trapping (`trap.wasm`) kernel surfaces
 * as [WasmExecutionException] — the loop bounded by the execution timeout without hanging — and
 * that a timed-out worker recovers for the next invocation.
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

    private val loopWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/loop.wasm",
        ),
    ) { "loop.wasm not found on classpath" }
        .readBytes()

    private val trapWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/trap.wasm",
        ),
    ) { "trap.wasm not found on classpath" }
        .readBytes()

    private val noabiWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/noabi.wasm",
        ),
    ) { "noabi.wasm not found on classpath" }
        .readBytes()

    private val nomemoryWasm: ByteArray = checkNotNull(
        ChicoryWasmRuntimeTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/nomemory.wasm",
        ),
    ) { "nomemory.wasm not found on classpath" }
        .readBytes()

    // --- Run-guard tests (Task 4) ---

    /**
     * An infinite-loop kernel is interrupted at the (short) execution timeout and surfaces
     * [WasmExecutionException]. The guest runs on REAL wall-clock time, so the `runTest` timeout
     * sits well above the 200 ms sandbox bound; the test must still complete promptly (not hang).
     */
    @Test
    fun infiniteLoopIsBoundedByTimeout() = runTest(timeout = 10.seconds) {
        ChicoryWasmRuntime(WasmSandboxConfig(executionTimeout = 200.milliseconds)).use { rt ->
            assertFailsWith<WasmExecutionException> { rt.load(loopWasm).invoke(ByteArray(0)) }
        }
    }

    @Test
    fun trapSurfacesAsExecutionException() = runTest(timeout = 10.seconds) {
        assertFailsWith<WasmExecutionException> { runtime.load(trapWasm).invoke(ByteArray(0)) }
    }

    /**
     * A timed-out invocation interrupts the dedicated worker thread; the next invocation on the
     * SAME runtime must still produce correct bytes — proving the stale interrupt does not poison
     * the recovered worker.
     */
    @Test
    fun timeoutDoesNotPoisonNextInvoke() = runTest(timeout = 10.seconds) {
        ChicoryWasmRuntime(WasmSandboxConfig(executionTimeout = 200.milliseconds)).use { rt ->
            assertFailsWith<WasmExecutionException> { rt.load(loopWasm).invoke(ByteArray(0)) }
            assertContentEquals(
                byteArrayOf(4, 3, 2, 1),
                rt.load(reverseWasm).invoke(byteArrayOf(1, 2, 3, 4)),
            )
        }
    }

    /**
     * Shared-runtime concurrency guard for the per-runtime [invokeMutex] (Defect 2). One runtime
     * serves several concurrently-running ops; a [us.tractat.kuilt.warp.WarpNode] runs owned tasks
     * concurrently over a single shared runtime. A runaway infinite-loop kernel and an innocent
     * `reverse` op are launched concurrently over one runtime; the innocent op must complete with
     * the correct bytes and the runaway must be bounded by the timeout — proving the serialization
     * neither deadlocks nor starves a concurrent caller, and that the innocent op gets its full
     * execution budget from when it actually runs (post-mutex) rather than from when it queued.
     *
     * On the literal false-timeout this defends against: it is a near-simultaneous-submit *race*,
     * not a deterministic failure. With one shared per-runtime timeout `T` and the interpreter's
     * prompt interrupt-freeing, a later-submitted op's deadline (`submit + T`) always trails each
     * earlier runaway's free-time (its own `submit + T`), so it cannot be deterministically
     * false-timed. A reproducible false-timeout would need *completing* slow kernels stacked so
     * cumulative queue-wait exceeds the budget — i.e. a test gated on real wall-clock durations,
     * which this repo's coroutine-determinism policy forbids. So this asserts the post-fix
     * guarantee (concurrent ops over one runtime all succeed) deterministically, instead of
     * chasing a flaky red. The 30 s `runTest` ceiling is a safety bound, not a timed assertion.
     */
    @Test
    fun concurrentOpsOverSharedRuntimeAllSucceed() = runTest(timeout = 30.seconds) {
        ChicoryWasmRuntime(WasmSandboxConfig(executionTimeout = 200.milliseconds)).use { rt ->
            val loop = rt.load(loopWasm)
            val reverse = rt.load(reverseWasm)
            coroutineScope {
                val runaway = async { runCatching { loop.invoke(ByteArray(0)) } }
                val innocent = async { reverse.invoke(byteArrayOf(1, 2, 3, 4)) }
                val innocentBytes = innocent.await()
                val runawayOutcome = runaway.await()
                assertAll(
                    { assertContentEquals(byteArrayOf(4, 3, 2, 1), innocentBytes, "innocent op succeeds") },
                    {
                        assertTrue(
                            runawayOutcome.exceptionOrNull() is WasmExecutionException,
                            "runaway op is bounded by the timeout",
                        )
                    },
                )
            }
        }
    }

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

    /**
     * A well-formed module that parses, declares no imports, and has legal memory, but omits the
     * `warp_alloc`/`warp_run` ABI exports. Chicory's `Instance.export(...)` throws a raw
     * `InvalidException` (a `ChicoryException`, NOT a [WasmException]) for the missing export;
     * [ChicoryWasmRuntime.load] must convert that into a TERMINAL [WasmLoadException] so the
     * executor treats the broken kernel as converged, not a transient error it retries forever.
     */
    @Test
    fun loadRejectsModuleMissingAbiExports() {
        assertFailsWith<WasmLoadException> { runtime.load(noabiWasm) }
    }

    /**
     * A module that exports the ABI functions but no memory section: `Instance.memory()` returns
     * null. [ChicoryWasmRuntime.load] must surface that as a terminal [WasmLoadException] rather
     * than letting a downstream NPE escape as a transient executor error.
     */
    @Test
    fun loadRejectsModuleMissingMemoryExport() {
        assertFailsWith<WasmLoadException> { runtime.load(nomemoryWasm) }
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
