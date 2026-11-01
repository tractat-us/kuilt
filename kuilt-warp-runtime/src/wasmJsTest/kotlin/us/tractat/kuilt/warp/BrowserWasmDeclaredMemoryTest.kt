package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.test.WasmKernelFixtures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The parts of the browser's memory-section parser the shared TCK cannot reach, because V8 refuses
 * those modules first or because the other runtimes disagree about them.
 */
class BrowserWasmDeclaredMemoryTest {

    /**
     * V8 refuses any module carrying these limits, so no module can put them in front of the cap
     * check today (see the TCK's `loadRejectsMemory64MaxPastThe32BitRange`). The decode is what
     * would stand between the sandbox and an engine that raised its limit, so it is read directly.
     */
    @Test
    fun lebDecodesPastThe32BitRange() {
        fun decode(vararg bytes: Int) = WasmByteReader(ByteArray(bytes.size) { bytes[it].toByte() }, 0).leb().toLong()
        assertAll(
            { assertEquals(1L shl 32, decode(0x80, 0x80, 0x80, 0x80, 0x10), "2^32 must not overflow to 0") },
            { assertEquals(1L shl 31, decode(0x80, 0x80, 0x80, 0x80, 0x08), "2^31 must not go negative") },
            { assertEquals(16L, decode(0x10), "a one-byte value still reads as itself") },
        )
    }

    /**
     * Limits inside the cap, so the flag is the only thing wrong, and V8 accepts the module: this is
     * the one memory64 shape that reaches the parser. Browser-only because wasm3 reads flag `0x05`
     * as "has a max" and runs the module with a plain 32-bit memory under the same cap, which is
     * not a breach. The message attributes the rejection to this guard.
     */
    @Test
    fun loadRejectsMemory64LimitsWithinTheCap() {
        val rejected = assertFailsWith<WasmLoadException> {
            BrowserWasmRuntime().load(WasmKernelFixtures.MEMORY64_WITHIN_CAP)
        }
        assertContains(rejected.message ?: "", "memory64")
    }
}
