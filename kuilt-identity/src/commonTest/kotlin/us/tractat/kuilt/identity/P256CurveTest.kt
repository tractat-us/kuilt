package us.tractat.kuilt.identity

import com.ionspin.kotlin.bignum.integer.BigInteger
import dev.whyoleg.cryptography.algorithms.EC
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [decompress] turns a 33-byte compressed point into the 65-byte `0x04 ‖ x ‖ y` form every provider
 * imports, Safari's WebCrypto included (#3232). Its answers are checked against points computed
 * outside this module, and against each provider's own export.
 */
class P256CurveTest {
    @Test
    fun decompressMatchesKnownPoints() {
        val rows = KNOWN_POINTS.map { (compressed, uncompressed) -> Triple(compressed, uncompressed, decompress(compressed.hexToByteArray())) }
        assertAll(
            // Preconditions: the vectors cover both lead bytes and both leading-zero shapes, so a parity
            // or padding mistake has a row that shows it.
            { assertTrue(KNOWN_POINTS.any { it.first.startsWith("02") } && KNOWN_POINTS.any { it.first.startsWith("03") }, "both parities") },
            { assertEquals("00", K379_UNCOMPRESSED.substring(2, 4), "precondition: 379·G's x has a zero high byte") },
            { assertEquals("00", K43_UNCOMPRESSED.substring(66, 68), "precondition: 43·G's y has a zero high byte (y < 2^248)") },
            *rows.map { (compressed, uncompressed, actual) ->
                { assertEquals(uncompressed, actual?.toHexString(), "decompress($compressed)") }
            }.toTypedArray(),
        )
    }

    @Test
    fun decompressAgreesWithTheProviderOnGeneratedKeys() = runTest {
        val ecdsa = algorithms().ecdsa
        val rows = (1..GENERATED_KEYS).map {
            val publicKey = ecdsa.keyPairGenerator(EC.Curve.P256).generateKey().publicKey
            val compressed = publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
            val uncompressed = publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
            Triple(compressed, uncompressed, decompress(compressed))
        }
        assertAll(
            { assertEquals(GENERATED_KEYS, rows.size, "every generated key ran") },
            { assertTrue(rows.all { it.second.size == UNCOMPRESSED_SIZE }, "precondition: the provider exports 65 bytes") },
            *rows.map { (compressed, uncompressed, actual) ->
                { assertEquals(uncompressed.toHexString(), actual?.toHexString(), "decompress(${compressed.toHexString()})") }
            }.toTypedArray(),
        )
    }

    @Test
    fun decompressRefusesOffCurveAndOutOfRange() {
        val xEqualsP = byteArrayOf(0x02) + P256.P.toFixedBytes()
        val xEqualsPPlus5 = byteArrayOf(0x02) + (P256.P + BigInteger(5)).toFixedBytes()
        val generator = GENERATOR_COMPRESSED.hexToByteArray()
        assertAll(
            // Preconditions: x mod p has a point in both out-of-range rows, so only `x < p` refuses them.
            { assertTrue(P256.hasPointAt(BigInteger.ZERO), "precondition: p mod p = 0 has a point") },
            { assertTrue(P256.hasPointAt(BigInteger(5)), "precondition: (p + 5) mod p = 5 has a point") },
            { assertTrue(decompress(generator) != null, "precondition: the generator decompresses") },
            { assertEquals(null, decompress(OFF_CURVE_COMPRESSED.hexToByteArray())?.toHexString(), "x = 1 has no point") },
            { assertEquals(null, decompress(xEqualsP)?.toHexString(), "x = p") },
            { assertEquals(null, decompress(xEqualsPPlus5)?.toHexString(), "x = p + 5") },
            { assertEquals(null, decompress(generator.copyOf().also { it[0] = 0x04 })?.toHexString(), "a 0x04 lead byte") },
            { assertEquals(null, decompress(generator.copyOf().also { it[0] = 0x00 })?.toHexString(), "a 0x00 lead byte") },
            { assertEquals(null, decompress(generator.copyOf(32))?.toHexString(), "32 bytes") },
            { assertEquals(null, decompress(generator + 0x00)?.toHexString(), "34 bytes") },
        )
    }

    /**
     * The real path: [decodeOnCurve] hands the provider [decompress]'s output. A short `y` or `x` is a
     * 64-byte input every provider refuses, so the leading-zero rows fail here as well as above.
     */
    @Test
    fun decodeOnCurveImportsTheUncompressedFormOnEveryTarget() = runTest {
        val rows = KNOWN_POINTS.map { (compressed, uncompressed) ->
            Triple(compressed, uncompressed, decodeOnCurve(compressed.hexToByteArray())?.encodeToByteArray(EC.PublicKey.Format.RAW))
        }
        assertAll(*rows.map { (compressed, uncompressed, actual) -> { assertEquals(uncompressed, actual?.toHexString(), "decodeOnCurve($compressed)") } }.toTypedArray())
    }

    private fun BigInteger.toFixedBytes(): ByteArray {
        val magnitude = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        check(magnitude.size <= 32) { "does not fit in 32 bytes" }
        return ByteArray(32 - magnitude.size) + magnitude
    }

    private companion object {
        const val UNCOMPRESSED_SIZE = 65
        const val GENERATED_KEYS = 32

        // SEC 2 v2.0 § 2.4.2, secp256r1's base point G. Gy is odd, so the lead byte is 0x03.
        const val GENERATOR_COMPRESSED = "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        const val GENERATOR_UNCOMPRESSED =
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"

        // x = 1, which has no point on P-256. Measured by #3201.
        const val OFF_CURVE_COMPRESSED = "020000000000000000000000000000000000000000000000000000000000000001"

        // 43·G and 379·G, computed with affine point arithmetic in Python, independent of this module
        // and of every provider; each checked against y² = x³ − 3x + b. 43·G is the first multiple of G
        // whose y has a zero high byte, and 379·G the first whose x does.
        const val K43_COMPRESSED = "03986ae2506f1ff104d04230861d8f4b498f4bc4c6d009b30f7544dc129b82d28d"
        const val K43_UNCOMPRESSED =
            "04986ae2506f1ff104d04230861d8f4b498f4bc4c6d009b30f7544dc129b82d28d" +
                "003cccc0a6460e0ae328a4d97d3c7b61d86fc6289c189f2525110c441bb07e97"
        const val K379_COMPRESSED = "02005543894af3d00ed7d740abdbd75c96b06877b787db5f70eea78b90a8d7c00a"
        const val K379_UNCOMPRESSED =
            "04005543894af3d00ed7d740abdbd75c96b06877b787db5f70eea78b90a8d7c00a" +
                "bb4c85a3d8ea29efaafa24406912dd84d5b14dc32bf656ef6c6bd58a5d943f92"

        // x = 5, the smallest positive x with a point, in both parities: 31 zero bytes of x to pad.
        const val X5_EVEN_COMPRESSED = "020000000000000000000000000000000000000000000000000000000000000005"
        const val X5_EVEN_UNCOMPRESSED =
            "040000000000000000000000000000000000000000000000000000000000000005" +
                "459243b9aa581806fe913bce99817ade11ca503c64d9a3c533415c083248fbcc"
        const val X5_ODD_COMPRESSED = "030000000000000000000000000000000000000000000000000000000000000005"
        const val X5_ODD_UNCOMPRESSED =
            "040000000000000000000000000000000000000000000000000000000000000005" +
                "ba6dbc4555a7e7fa016ec431667e8521ee35afc49b265c3accbea3f7cdb70433"

        /** Compressed → uncompressed. The first two are G0's fixed points from [CompressedPointProbeTest]. */
        val KNOWN_POINTS = listOf(
            "02bb5e9f71cd43e4452723479973d0326f901ed963fee59f419bba6cd8649ffb17" to
                "04bb5e9f71cd43e4452723479973d0326f901ed963fee59f419bba6cd8649ffb17" +
                "98515f04bf483ceaf9c437c4ee6b99736a2ead659fca525e8bd76694fdf7cd6c",
            "0339a9e222559fa1d3da385b00368b9d29258895752e83e3f9d34dfd29d957e296" to
                "0439a9e222559fa1d3da385b00368b9d29258895752e83e3f9d34dfd29d957e296" +
                "4f407eeb4111d32aa16b20c62870177cbb7d65750a7f821d336faa4fdba316cb",
            GENERATOR_COMPRESSED to GENERATOR_UNCOMPRESSED,
            K43_COMPRESSED to K43_UNCOMPRESSED,
            K379_COMPRESSED to K379_UNCOMPRESSED,
            X5_EVEN_COMPRESSED to X5_EVEN_UNCOMPRESSED,
            X5_ODD_COMPRESSED to X5_ODD_UNCOMPRESSED,
        )
    }
}
