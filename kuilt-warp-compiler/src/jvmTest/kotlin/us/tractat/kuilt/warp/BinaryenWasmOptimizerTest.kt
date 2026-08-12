@file:Suppress("ForbiddenImport") // deliberate real-subprocess test: wasm-opt is genuine wall-clock work (the sanctioned real-threading exception, like ChicoryWasmRuntime's real-IO path), so the optimizer is given Dispatchers.IO, not a virtual-time dispatcher — runTest has no delays to drive.

package us.tractat.kuilt.warp

import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-subprocess test: wasm-opt is genuine wall-clock work (the sanctioned real-threading exception), so the optimizer is given Dispatchers.IO — runTest has no delays to drive.
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.test.WasmKernelFixtures
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic CI proof that [BinaryenWasmOptimizer] runs the real bundled
 * `wasm-opt` and produces a **valid, smaller, ABI-preserving, distinct-hash**
 * variant of a representative real kernel.
 *
 * The kernel is [WasmKernelFixtures.REVERSE] — the well-behaved warp kernel the
 * runtime conformance suite exercises (full `warp_alloc`/`warp_run` ABI, a real
 * loop over linear memory), not a hand-minimal `.wat` that `wasm-opt` would
 * barely touch. These run un-gated in CI because the build host's own
 * `resolveWasmOpt<Platform>` task wires its binary straight into `jvmTest` resources —
 * the tests never declare the consumer-facing classified dependency (#1335).
 *
 * `wasm-opt` is a real subprocess doing real, wall-clock, blocking I/O — the
 * sanctioned real-threading exception (like [ChicoryWasmRuntime]'s real-IO path).
 * The optimizer is therefore given [Dispatchers.IO], not a virtual-time test
 * dispatcher; `runTest` has no delays to drive, it just awaits the real work.
 */
class BinaryenWasmOptimizerTest {

    // Real subprocess I/O — the sanctioned real-threading exception; no virtual clock to drive.
    private val optimizer = BinaryenWasmOptimizer(Dispatchers.IO)

    private val source = WasmKernelFixtures.REVERSE

    @Test
    fun optimizingLevelsProduceValidSmallerAbiPreservingDistinctVariants() = runTest {
        // O0 is a passthrough; the speed/size levels each produce a real variant.
        for (level in listOf(OptLevel.O2, OptLevel.O3, OptLevel.Oz)) {
            val optimized = optimizer.optimize(source, level)
            // Load + run the optimized module through the real runtime up front (suspending):
            // a successful load proves validity + surviving warp_alloc/warp_run exports, and a
            // correct byte-reversal proves the optimization preserved behaviour.
            val output = runReverse(optimized)
            assertAll(
                { assertTrue(optimized.size < source.size, "$level: optimized (${optimized.size}) must be smaller than source (${source.size})") },
                { assertTrue(!optimized.contentEquals(source), "$level: optimized bytes must differ from source") },
                { assertTrue(sha256(optimized) != sha256(source), "$level: optimized hash must differ from source hash") },
                { assertContentEquals(byteArrayOf(4, 3, 2, 1), output, "$level: optimized module must still load + run the warp ABI") },
            )
        }
    }

    @Test
    fun optLevelO0IsAPassthrough() = runTest {
        val optimized = optimizer.optimize(source, OptLevel.O0)
        assertAll(
            { assertContentEquals(source, optimized, "O0 returns the source unchanged") },
            { assertEquals(sha256(source), sha256(optimized), "O0 preserves the source hash") },
        )
    }

    /** Loads [wasm] through the real [ChicoryWasmRuntime] and runs the warp ABI on `[1,2,3,4]`. */
    private suspend fun runReverse(wasm: ByteArray): ByteArray =
        ChicoryWasmRuntime().use { runtime -> runtime.load(wasm).invoke(byteArrayOf(1, 2, 3, 4)) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
