package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasmOptimizerTest {

    @Test
    fun optLevelHasFourRungs() {
        assertAll(
            { assertEquals(4, OptLevel.entries.size) },
            { assertTrue(OptLevel.O0 in OptLevel.entries) },
            { assertTrue(OptLevel.O2 in OptLevel.entries) },
            { assertTrue(OptLevel.O3 in OptLevel.entries) },
            { assertTrue(OptLevel.Oz in OptLevel.entries) },
        )
    }

    @Test
    fun passthroughReturnsInputUnchanged() = runTest {
        val bytes = MINIMAL_WASM
        val results = OptLevel.entries.map { it to PassthroughWasmOptimizer.optimize(bytes, it) }
        assertAll(
            *results.map { (level, out) -> { assertContentEquals(bytes, out, "level $level altered the bytes") } }
                .toTypedArray(),
        )
    }

    @Test
    fun passthroughIsAWasmOptimizerSeam() = runTest {
        val seam: WasmOptimizer = PassthroughWasmOptimizer
        val bytes = byteArrayOf(1, 2, 3)
        assertContentEquals(bytes, seam.optimize(bytes, OptLevel.O3))
    }
}
