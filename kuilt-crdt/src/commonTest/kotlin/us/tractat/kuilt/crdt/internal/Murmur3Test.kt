package us.tractat.kuilt.crdt.internal

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Golden-vector tests for [Murmur3] pinned to the canonical smhasher reference
 * (MurmurHash3_x86_32 by Austin Appleby, https://github.com/aappleby/smhasher).
 *
 * These vectors are the ground truth for the wire format of every CRDT that uses
 * this hash. A failure here is a **wire-breaking** regression.
 *
 * Hex annotations show the canonical unsigned value; the Kotlin literal is the
 * signed two's-complement representation of the same bits.
 *
 * All vectors were independently computed from the reference algorithm and verified
 * against the smhasher C output.
 */
class Murmur3Test {

    // ── Canonical boundary vectors (seed = 0) ────────────────────────────────

    @Test
    fun emptyInput() {
        // 0x00000000
        assertEquals(0, Murmur3.hash32(byteArrayOf()))
    }

    @Test
    fun singleZeroByte() {
        // 0x514E28B7 = 1364076727
        assertEquals(1364076727, Murmur3.hash32(byteArrayOf(0)))
    }

    // ── Tail lengths: pins the len&3 switch, the most error-prone part ────────

    @Test
    fun tailLength1() {
        // "a" (1 byte) → 0x3C2569B2 = 1009084850
        assertEquals(1009084850, Murmur3.hash32("a"))
    }

    @Test
    fun tailLength2() {
        // "ab" (2 bytes) → 0x9BBFD75F = -1681926305 (signed)
        assertEquals(-1681926305, Murmur3.hash32("ab"))
    }

    @Test
    fun tailLength3() {
        // "abc" (3 bytes) → 0xB3DD93FA = -1277324294 (signed)
        assertEquals(-1277324294, Murmur3.hash32("abc"))
    }

    @Test
    fun exactFourByteBlock() {
        // "abcd" (4 bytes, one complete block, no tail) → 0x43ED676A = 1139631978
        assertEquals(1139631978, Murmur3.hash32("abcd"))
    }

    // ── Multi-block inputs: pins the body loop ────────────────────────────────

    @Test
    fun twoCompleteBlocks() {
        // "abcdefgh" (8 bytes, 2 blocks, no tail) → 0x49DDCCC4 = 1239272644
        assertEquals(1239272644, Murmur3.hash32("abcdefgh"))
    }

    @Test
    fun twoBlocksPlusOneByteTail() {
        // "abcdefghi" (9 bytes, 2 blocks + 1-byte tail) → 0x421406F0 = 1108608752
        assertEquals(1108608752, Murmur3.hash32("abcdefghi"))
    }

    // ── Well-known ASCII strings ──────────────────────────────────────────────

    @Test
    fun helloVector() {
        // "hello" → 0x248BFA47 = 613153351
        assertEquals(613153351, Murmur3.hash32("hello"))
    }

    @Test
    fun testVector() {
        // "test" → 0xBA6BD213 = -1167338989 (signed)
        assertEquals(-1167338989, Murmur3.hash32("test"))
    }

    @Test
    fun longerAsciiVectors() {
        assertAll(
            // "hello world" (11 bytes) → 0x5E928F0F = 1586663183
            { assertEquals(1586663183, Murmur3.hash32("hello world")) },
            // "The quick brown fox" → 0x60A2C22D = 1621279277
            { assertEquals(1621279277, Murmur3.hash32("The quick brown fox")) },
            // "foobar" → 0xA4C4D4BD = -1530604355 (signed)
            { assertEquals(-1530604355, Murmur3.hash32("foobar")) },
            // "kuilt" → 0x91838DA3 = -1853649501 (signed)
            { assertEquals(-1853649501, Murmur3.hash32("kuilt")) },
        )
    }

    // ── UTF-8 non-ASCII: pins encodeToByteArray() correctness ─────────────────

    @Test
    fun nonAsciiUtf8Vectors() {
        assertAll(
            // "café" (UTF-8: 0x63,0x61,0x66,0xC3,0xA9 = 5 bytes) → 0x241C0F08 = 605818632
            { assertEquals(605818632, Murmur3.hash32("café")) },
            // "こんにちは" (UTF-8: 15 bytes) → 0x2D2241DC = 757219804
            { assertEquals(757219804, Murmur3.hash32("こんにちは")) },
        )
    }

    // ── Non-zero seeds ────────────────────────────────────────────────────────

    @Test
    fun nonZeroSeedVectors() {
        assertAll(
            // hash("hello", 42) → 0xE2DBD2E1 = -488910111 (signed)
            { assertEquals(-488910111, Murmur3.hash32("hello", seed = 42)) },
            // hash("hello", 613153351) — seed = hash("hello", 0), as used by Bloom h2
            // → 0xA01540B1 = -1609219919 (signed)
            { assertEquals(-1609219919, Murmur3.hash32("hello", seed = 613153351)) },
        )
    }

    @Test
    fun emptySeedVariation() {
        // hash("", 1) = hash("", 0) for len=0 since seed XOR 0 = seed,
        // then fmix(seed XOR 0) — should differ from seed=0 which gives 0.
        // Canonical: hash("", 1) → 0x514E28B7 = 1364076727
        assertEquals(1364076727, Murmur3.hash32(byteArrayOf(), seed = 1))
    }

    // ── String overload vs ByteArray overload ─────────────────────────────────

    @Test
    fun stringOverloadMatchesBytesOverload() {
        val text = "hello world"
        assertEquals(
            Murmur3.hash32(text.encodeToByteArray(), seed = 0),
            Murmur3.hash32(text, seed = 0),
        )
    }

    @Test
    fun nonAsciiStringOverloadMatchesBytesOverload() {
        val text = "café"
        assertEquals(
            Murmur3.hash32(text.encodeToByteArray(), seed = 0),
            Murmur3.hash32(text, seed = 0),
        )
    }

    // ── One-block body + each tail length: pins loop-then-tail interaction ──────
    //
    // The 0-block tests above (tailLength1/2/3) exercise the tail in isolation.
    // These exercise the body loop AND the tail together, catching bugs that only
    // surface when the two mix (e.g. off-by-one in the block-end offset calculation).

    @Test
    fun oneBlockPlusOneByteTail() {
        // "abcde" (5 bytes: 1 block "abcd" + 1-byte tail "e") → 0xE89B9AF6 = -392455434 (signed)
        assertEquals(-392455434, Murmur3.hash32("abcde"))
    }

    @Test
    fun oneBlockPlusTwoByteTail() {
        // "abcdef" (6 bytes: 1 block + 2-byte tail) → 0x6181C085 = 1635893381
        assertEquals(1635893381, Murmur3.hash32("abcdef"))
    }

    @Test
    fun oneBlockPlusThreeByteTail() {
        // "abcdefg" (7 bytes: 1 block + 3-byte tail) → 0x883C9B06 = -2009294074 (signed)
        assertEquals(-2009294074, Murmur3.hash32("abcdefg"))
    }

    // ── High-bit (≥ 0x80) bytes: pins the `and 0xFF` masks in readLittleEndianInt
    //    and readTail. Kotlin Byte is signed; without the mask, 0xFF is sign-extended
    //    to 0xFFFFFFFF, corrupting the mix. Each test places 0xFF in a different
    //    byte position so every shift/OR path is exercised. ────────────────────────

    @Test
    fun highBitInBlockBytePosition0() {
        // 0xFF in little-endian position 0 of the block → 0xEC306C59 = -332370855 (signed)
        assertEquals(-332370855, Murmur3.hash32(byteArrayOf(0xFF.toByte(), 0, 0, 0)))
    }

    @Test
    fun highBitInBlockBytePosition1() {
        // 0xFF in little-endian position 1 → 0x00305D31 = 3169585
        assertEquals(3169585, Murmur3.hash32(byteArrayOf(0, 0xFF.toByte(), 0, 0)))
    }

    @Test
    fun highBitInBlockBytePosition2() {
        // 0xFF in little-endian position 2 → 0x36226E67 = 908226151
        assertEquals(908226151, Murmur3.hash32(byteArrayOf(0, 0, 0xFF.toByte(), 0)))
    }

    @Test
    fun highBitInBlockBytePosition3() {
        // 0xFF in little-endian position 3 → 0x82D34F5D = -2100080803 (signed)
        assertEquals(-2100080803, Murmur3.hash32(byteArrayOf(0, 0, 0, 0xFF.toByte())))
    }

    @Test
    fun allHighBitBlock() {
        // All four bytes 0xFF in one block — maximum unsigned value per lane.
        // → 0x76293B50 = 1982413648
        assertEquals(1982413648, Murmur3.hash32(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }

    @Test
    fun highBitInTailLength1() {
        // Block "abcd" + 1-byte tail 0xFF — pins `and 0xFF` in readTail at remaining=1.
        // → 0x3825DAFC = 942004988
        assertEquals(942004988, Murmur3.hash32(byteArrayOf(0x61, 0x62, 0x63, 0x64, 0xFF.toByte())))
    }

    @Test
    fun highBitInTailLength2() {
        // Block "abcd" + 2-byte tail [0xFF, 0xFF] — pins `and 0xFF` at remaining=2.
        // → 0xE3327A6A = -483231126 (signed)
        assertEquals(-483231126, Murmur3.hash32(byteArrayOf(0x61, 0x62, 0x63, 0x64, 0xFF.toByte(), 0xFF.toByte())))
    }

    @Test
    fun highBitInTailLength3() {
        // Block "abcd" + 3-byte tail [0xFF, 0xFF, 0xFF] — pins `and 0xFF` at remaining=3.
        // → 0xEFB1C5DC = -273562148 (signed)
        assertEquals(-273562148, Murmur3.hash32(byteArrayOf(0x61, 0x62, 0x63, 0x64, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }

    // ── k1 == 0 guard: pins the `if (k1 != 0)` branch being false for a non-empty
    //    tail. When the tail bytes all XOR to zero the tail-mix is correctly skipped
    //    (it would be a no-op anyway, but the guard must not corrupt the output). ──

    @Test
    fun zeroTailByteSkipsMix() {
        // Block "abcd" + 1-byte tail 0x00.
        // readTail returns 0; the `if (k1 != 0)` branch is NOT taken.
        // Result must differ from hash("abcd") only by the `xor len` in finalization
        // (len=5 vs 4), not by any tail mixing. → 0xD8C0D9EA = -658449942 (signed)
        assertEquals(-658449942, Murmur3.hash32(byteArrayOf(0x61, 0x62, 0x63, 0x64, 0)))
    }

    // ── Long input (≥ 64 bytes): exercises many body-loop iterations ─────────────

    @Test
    fun longInput() {
        // 64 bytes of 'a' (0x61) — 16 body blocks, no tail.
        // → 0xEE9D2997 = -291690089 (signed)
        assertEquals(-291690089, Murmur3.hash32(ByteArray(64) { 0x61.toByte() }))
    }

    // ── Additional non-zero seeds: confirms seed actually propagates through ─────
    //    the entire computation (fmix, block mixing), not just the initial state. ─

    @Test
    fun thirdDistinctNonZeroSeed() {
        assertAll(
            // hash("hello", 1) → 0xBB4ABCAD = -1152729939 (signed)
            { assertEquals(-1152729939, Murmur3.hash32("hello", seed = 1)) },
            // hash("hello", 1588444911) → 0x5D3CB105 = 1564258565
            { assertEquals(1564258565, Murmur3.hash32("hello", seed = 1588444911)) },
        )
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    fun deterministicOnRepeatedCalls() {
        val input = "kuilt-crdt-murmur3"
        assertEquals(Murmur3.hash32(input), Murmur3.hash32(input))
    }

    @Test
    fun differentSeedsProduceDifferentHashes() {
        val bytes = "hello".encodeToByteArray()
        assertNotEquals(Murmur3.hash32(bytes, seed = 0), Murmur3.hash32(bytes, seed = 1))
    }
}
