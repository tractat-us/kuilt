package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate G0 for the device identity: every provider this module ships on must read and write a P-256
 * public key as a 33-byte compressed point, because a `did:key` name carries exactly that.
 *
 * On wasmJs the two directions rest on different code. Export compresses in the library, so it is
 * the same in every browser. Import hands the 33 bytes to the browser's own `importKey("raw")`,
 * where compressed points are optional in the WebCrypto spec, so a green here proves import only
 * in the browser the tests ran in. Every arm runs through the module's self-tested [algorithms],
 * so a provider that cannot start fails the arm instead of passing it by absence.
 */
class CompressedPointProbeTest {
    @Test
    fun compressedExportIsThirtyThreeBytes() = runTest {
        val ecdsa = algorithms().ecdsa
        val pair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKey()
        val compressed = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
        assertAll(
            { assertEquals(COMPRESSED_SIZE, compressed.size, "compressed export size") },
            { assertTrue(compressed[0] == 0x02.toByte() || compressed[0] == 0x03.toByte(), "lead byte ${compressed[0]}") },
        )
    }

    @Test
    fun compressedImportRoundTripsToTheSamePoint() = runTest {
        val ecdsa = algorithms().ecdsa
        val pair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKey()
        val uncompressed = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
        val compressed = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
        val decoded = ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, compressed)
        val reExported = decoded.encodeToByteArray(EC.PublicKey.Format.RAW)
        assertAll(
            // Precondition: the original really is the 65-byte SEC 1 form, so equality below means something.
            { assertEquals(UNCOMPRESSED_SIZE, uncompressed.size, "uncompressed export size") },
            { assertEquals(uncompressed.toHexString(), reExported.toHexString(), "decoded compressed point re-exports the original") },
        )
    }

    @Test
    fun importedCompressedKeyVerifies() = runTest {
        val ecdsa = algorithms().ecdsa
        val pair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKey()
        val compressed = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
        val decoded = ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, compressed)
        val message = "a device comes back".encodeToByteArray()
        val signature = pair.privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW).generateSignature(message)
        val flipped = signature.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        val verifier = decoded.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
        val good = verifier.tryVerifySignature(message, signature)
        val bad = verifier.tryVerifySignature(message, flipped)
        assertAll(
            { assertEquals(RAW_SIGNATURE_SIZE, signature.size, "raw r||s signature size") },
            { assertTrue(good, "the decoded compressed key verifies the original key's signature") },
            { assertFalse(bad, "negative control: a signature with one flipped bit must not verify") },
        )
    }

    @Test
    fun knownCompressedPointDecodesOnEveryTarget() = runTest {
        val decoder = algorithms().ecdsa.publicKeyDecoder(EC.Curve.P256)
        // Both parities: the 0x02 (even y) and 0x03 (odd y) branches of decompression differ.
        val even = decoder.decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, EVEN_COMPRESSED.hexToByteArray())
            .encodeToByteArray(EC.PublicKey.Format.RAW)
        val odd = decoder.decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, ODD_COMPRESSED.hexToByteArray())
            .encodeToByteArray(EC.PublicKey.Format.RAW)
        assertAll(
            { assertEquals(EVEN_UNCOMPRESSED, even.toHexString(), "even-y point (0x02)") },
            { assertEquals(ODD_UNCOMPRESSED, odd.toHexString(), "odd-y point (0x03)") },
        )
    }

    private companion object {
        const val COMPRESSED_SIZE = 33
        const val UNCOMPRESSED_SIZE = 65
        const val RAW_SIGNATURE_SIZE = 64

        // Generated once on the JDK (java.security, secp256r1) and checked on the curve equation
        // independently; pasted here so every target decodes the same bytes.
        const val EVEN_COMPRESSED = "02bb5e9f71cd43e4452723479973d0326f901ed963fee59f419bba6cd8649ffb17"
        const val EVEN_UNCOMPRESSED =
            "04bb5e9f71cd43e4452723479973d0326f901ed963fee59f419bba6cd8649ffb17" +
                "98515f04bf483ceaf9c437c4ee6b99736a2ead659fca525e8bd76694fdf7cd6c"
        const val ODD_COMPRESSED = "0339a9e222559fa1d3da385b00368b9d29258895752e83e3f9d34dfd29d957e296"
        const val ODD_UNCOMPRESSED =
            "0439a9e222559fa1d3da385b00368b9d29258895752e83e3f9d34dfd29d957e296" +
                "4f407eeb4111d32aa16b20c62870177cbb7d65750a7f821d336faa4fdba316cb"
    }
}
