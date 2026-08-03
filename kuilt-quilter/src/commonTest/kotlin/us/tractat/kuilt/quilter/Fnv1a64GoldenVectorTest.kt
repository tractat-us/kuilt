package us.tractat.kuilt.quilter

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Absolute cross-target pins for [fnv1a64].
 *
 * `commonTest` compiles and runs on JVM, Android, iOS, macOS and wasmJs, so these constants hold
 * every target to the same arithmetic — the property a peer-to-peer digest depends on and that a
 * JVM-only run cannot establish. The `"a"` vector is the published FNV-1a 64 test vector
 * (`0xaf63dc4c8601ec8c`), so a wrong basis or prime fails here rather than silently shipping a
 * hash that only agrees with itself.
 *
 * Note `Long` overflow is the *intended* arithmetic: FNV-1a is defined mod 2^64, and Kotlin's
 * wrapping `Long` multiply is exactly that.
 */
class Fnv1a64GoldenVectorTest {

    @Test
    fun pinnedVectors() = assertAll(
        { assertEquals(-3750763034362895579L, fnv1a64(ByteArray(0)), "empty input must be the offset basis") },
        { assertEquals(-5808556873153909620L, fnv1a64("a".encodeToByteArray()), "published FNV-1a 64 vector for \"a\"") },
        { assertEquals(-6382011383256120612L, fnv1a64("kuilt".encodeToByteArray()), "\"kuilt\"") },
        { assertEquals(4932904490461320209L, fnv1a64(byteArrayOf(0, 1, 2, 3)), "raw bytes incl. a zero byte") },
    )

    @Test
    fun highBitBytesAreFoldedUnsigned() {
        // The classic FNV port error is folding `byte.toLong()` without `and 0xFF`, so a byte >=
        // 0x80 sign-extends. It is invisible for ASCII, which is why every vector above would pass
        // with the bug present — only an ABSOLUTE pin on a high-bit byte catches it.
        //
        // Do NOT weaken these to relational assertions. `byteArrayOf(-1)` and
        // `byteArrayOf(0xFF.toByte())` are the same value, so comparing them is a tautology, and
        // 0x7F vs 0xFF differ under the bug too — both forms pass while broken.
        //
        // Reference values (correct / buggy) for 0xFF: -5808391946409677970 / 5808589858502755950.
        assertAll(
            { assertEquals(-5808391946409677970L, fnv1a64(byteArrayOf(0xFF.toByte())), "0xFF must fold as 255, not -1") },
            { assertEquals(-5808450220525973153L, fnv1a64(byteArrayOf(0x80.toByte())), "0x80 must fold as 128") },
        )
    }

    @Test
    fun orderMatters() =
        kotlin.test.assertNotEquals(
            fnv1a64(byteArrayOf(1, 2)),
            fnv1a64(byteArrayOf(2, 1)),
            "FNV-1a is order-sensitive; a commutative fold would break divergence detection",
        )
}
