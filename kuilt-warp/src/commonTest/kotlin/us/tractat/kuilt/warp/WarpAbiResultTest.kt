package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class WarpAbiResultTest {

    /** Packs a `warp_run` result exactly as a guest does: `(ptr << 32) | (len & 0xFFFF_FFFF)`. */
    private fun pack(ptr: Long, len: Long): Long = (ptr shl 32) or (len and 0xFFFF_FFFFL)

    // ── unpackWarpResult: unsigned arithmetic (the #953 regression class) ──────────────────────

    @Test
    fun unpackKeepsHighBitWordsUnsigned() = assertAll(
        { assertEquals(0x8000_0000L, unpackWarpResult(pack(0x8000_0000L, 4)).ptr, "high-bit ptr stays positive") },
        { assertEquals(4L, unpackWarpResult(pack(0x8000_0000L, 4)).len) },
        { assertEquals(0x8000_0000L, unpackWarpResult(pack(0, 0x8000_0000L)).len, "high-bit len stays positive") },
        { assertEquals(0xFFFF_FFFFL, unpackWarpResult(-1L).ptr, "all-ones packed word: ptr = u32 max") },
        { assertEquals(0xFFFF_FFFFL, unpackWarpResult(-1L).len, "all-ones packed word: len = u32 max") },
    )

    @Test
    fun unpackSplitsPointerAndLength() = assertAll(
        { assertEquals(16L, unpackWarpResult(pack(16, 4)).ptr) },
        { assertEquals(4L, unpackWarpResult(pack(16, 4)).len) },
        { assertEquals(0L, unpackWarpResult(0L).ptr) },
        { assertEquals(0L, unpackWarpResult(0L).len) },
    )

    // ── requireInBounds: the window predicate ───────────────────────────────────────────────────

    @Test
    fun windowEndingExactlyAtMemorySizeIsInBounds() {
        requireInBounds(ptr = 60, len = 4, memorySize = 64)
        requireInBounds(ptr = 0, len = 0, memorySize = 0)
    }

    @Test
    fun windowPastMemorySizeIsRejected() = assertAll(
        { assertFailsWith<WasmExecutionException> { requireInBounds(ptr = 61, len = 4, memorySize = 64) } },
        { assertFailsWith<WasmExecutionException> { requireInBounds(ptr = 64, len = 1, memorySize = 64) } },
        { assertFailsWith<WasmExecutionException> { requireInBounds(ptr = 0x8000_0000L, len = 4, memorySize = 65536) } },
    )

    @Test
    fun lengthOverIntMaxIsRejectedEvenInsideMemory() {
        // A ByteArray cannot hold it, so the decode must trap as a guest fault, not
        // surface a raw negative-size allocation failure.
        assertFailsWith<WasmExecutionException> {
            requireInBounds(ptr = 0, len = 0x8000_0000L, memorySize = 0x1_0000_0000L)
        }
    }

    @Test
    fun negativeWordsAreRejected() = assertAll(
        { assertFailsWith<WasmExecutionException> { requireInBounds(ptr = -1, len = 4, memorySize = 64) } },
        { assertFailsWith<WasmExecutionException> { requireInBounds(ptr = 0, len = -1, memorySize = 64) } },
    )

    // ── decodeWarpResult: the composed unpack + bounds-check + read ────────────────────────────

    @Test
    fun validDecodeRoundTrips() {
        val memory = ByteArray(64) { it.toByte() }
        val decoded = decodeWarpResult(pack(16, 4), memory.size.toLong()) { ptr, len ->
            memory.copyOfRange(ptr.toInt(), (ptr + len).toInt())
        }
        assertContentEquals(byteArrayOf(16, 17, 18, 19), decoded)
    }

    @Test
    fun oobResultPointerNeverReachesTheReader() {
        assertFailsWith<WasmExecutionException> {
            decodeWarpResult(pack(0x8000_0000L, 4), memorySize = 65536) { _, _ ->
                fail("reader must not run for an out-of-bounds result pointer")
            }
        }
    }

    @Test
    fun oobResultLengthNeverReachesTheReader() {
        assertFailsWith<WasmExecutionException> {
            decodeWarpResult(pack(0, 0x8000_0000L), memorySize = 65536) { _, _ ->
                fail("reader must not run for an out-of-bounds result length")
            }
        }
    }
}
