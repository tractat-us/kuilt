package us.tractat.kuilt.deal

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Locks the per-platform [sraModPow] fast path (native `java.math` on JVM/Android,
 * ionspin elsewhere) to a single canonical byte encoding. Each case runs on every
 * target, so a JVM/ionspin divergence — which would silently break cross-platform
 * SRA, since ciphertext is compared by raw bytes — fails the build here.
 */
class SraModPowTest {

    @Test
    fun goldenVectorsAreCanonicalAndPlatformIndependent() {
        assertAll(
            // 2^10 mod 1000 = 24
            { assertContentEquals(byteArrayOf(0x18), sraModPowCanonical(bytes(2), bytes(10), bytes(0x03, 0xE8))) },
            // 3^5 mod 251 = 243 (0xF3) — high bit set; java.math emits a leading 0x00 sign byte that must be stripped
            { assertContentEquals(byteArrayOf(0xF3.toByte()), sraModPowCanonical(bytes(3), bytes(5), bytes(0xFB))) },
            // 1234^5 mod 65521 = 54648 (0xD578) — two-byte result, high bit set in the top byte
            { assertContentEquals(byteArrayOf(0xD5.toByte(), 0x78), sraModPowCanonical(bytes(0x04, 0xD2), bytes(5), bytes(0xFF, 0xF1))) },
            // 2^3 mod 251 = 8 — small single byte, no normalization needed
            { assertContentEquals(byteArrayOf(0x08), sraModPowCanonical(bytes(2), bytes(3), bytes(0xFB))) },
        )
    }

    @Test
    fun canonicalMagnitudeStripsLeadingZeros() {
        assertAll(
            { assertContentEquals(byteArrayOf(0xF3.toByte()), canonicalMagnitude(byteArrayOf(0x00, 0xF3.toByte()))) },
            { assertContentEquals(byteArrayOf(0x01, 0x00), canonicalMagnitude(byteArrayOf(0x00, 0x00, 0x01, 0x00))) },
            { assertContentEquals(byteArrayOf(0x07), canonicalMagnitude(byteArrayOf(0x07))) },
            { assertContentEquals(byteArrayOf(), canonicalMagnitude(byteArrayOf(0x00, 0x00))) },
        )
    }

    @Test
    fun sraEncryptionCommutesOverTheRealPrime() {
        // The defining SRA property — E_A(E_B(m)) = E_B(E_A(m)) — exercised end-to-end
        // on the real 2048-bit modulus through whichever fast path this platform uses.
        val scheme = SraScheme()
        val a = scheme.generateKey()
        val b = scheme.generateKey()
        val m = encodePlaintext("card:ACE_OF_SPADES".encodeToByteArray())

        val ab = scheme.encrypt(scheme.encrypt(m, a.encryptKey).first, b.encryptKey).first
        val ba = scheme.encrypt(scheme.encrypt(m, b.encryptKey).first, a.encryptKey).first
        assertContentEquals(ab, ba)

        // …and stripping in either order recovers the plaintext.
        val recovered = scheme.strip(scheme.strip(ab, a.stripKey).first, b.stripKey).first
        assertEquals(m.toList(), recovered.toList())
    }

    private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }
}
