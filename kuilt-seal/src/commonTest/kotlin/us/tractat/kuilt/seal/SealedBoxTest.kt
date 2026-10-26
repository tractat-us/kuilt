package us.tractat.kuilt.seal

import kotlin.coroutines.cancellation.CancellationException
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sealed-box conformance properties, run on every target the module builds for — each target
 * seals and opens through its own platform provider (JCA, CryptoKit, WebCrypto).
 */
class SealedBoxTest {
    private val plaintext = "the full position, padded by the caller".encodeToByteArray()
    private val associatedData = "problem-report:v1".encodeToByteArray()

    @Test
    fun sealThenOpenReturnsThePlaintext() = runTest {
        val recipient = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        val opened = SealedBox.open(recipient, sealed, associatedData)
        assertAll(
            { assertContentEquals(plaintext, opened) },
            { assertEquals(plaintext.size + SealedBox.OVERHEAD, sealed.size) },
        )
    }

    @Test
    fun emptyPlaintextAndEmptyAssociatedDataRoundTrip() = runTest {
        val recipient = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, ByteArray(0), ByteArray(0))
        val opened = SealedBox.open(recipient, sealed, ByteArray(0))
        assertAll(
            { assertContentEquals(ByteArray(0), opened) },
            { assertEquals(SealedBox.OVERHEAD, sealed.size) },
        )
    }

    @Test
    fun twoSealsOfOnePlaintextDifferInEphemeralKeyAndNonce() = runTest {
        val recipient = SealPrivateKey.generate()
        val first = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        val second = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        assertAll(
            { assertFalse(first.contentEquals(second), "two seals of one plaintext were identical") },
            { assertFalse(first.ephemeralKey().contentEquals(second.ephemeralKey()), "ephemeral key reused") },
            { assertFalse(first.nonce().contentEquals(second.nonce()), "nonce reused") },
        )
    }

    @Test
    fun theWrongPrivateKeyDoesNotOpen() = runTest {
        val recipient = SealPrivateKey.generate()
        val stranger = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        val failure = assertFailsWith<SealedBoxException> { SealedBox.open(stranger, sealed, associatedData) }
        assertEquals(SealedBoxException.Reason.NOT_AUTHENTIC, failure.reason)
    }

    @Test
    fun differentAssociatedDataDoesNotOpen() = runTest {
        val recipient = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        val failure = assertFailsWith<SealedBoxException> {
            SealedBox.open(recipient, sealed, "problem-report:v2".encodeToByteArray())
        }
        assertEquals(SealedBoxException.Reason.NOT_AUTHENTIC, failure.reason)
    }

    @Test
    fun aFlippedBitAnywhereAfterTheVersionDoesNotOpen() = runTest {
        val recipient = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        // One position in each region: ephemeral key, nonce, ciphertext, tag.
        val positions = listOf(1, 40, 66, 77, 78, sealed.size - 17, sealed.size - 1)
        val reasons = positions.map { position ->
            val tampered = sealed.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
            assertFailsWith<SealedBoxException> { SealedBox.open(recipient, tampered, associatedData) }.reason
        }
        assertEquals(List(positions.size) { SealedBoxException.Reason.NOT_AUTHENTIC }, reasons)
    }

    @Test
    fun aTruncatedBoxIsMalformed() = runTest {
        val recipient = SealPrivateKey.generate()
        val failure = assertFailsWith<SealedBoxException> {
            SealedBox.open(recipient, ByteArray(SealedBox.OVERHEAD - 1) { 1 }, associatedData)
        }
        assertEquals(SealedBoxException.Reason.MALFORMED, failure.reason)
    }

    @Test
    fun anUnknownVersionIsRefused() = runTest {
        val recipient = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, plaintext, associatedData)
        sealed[0] = 2
        val failure = assertFailsWith<SealedBoxException> { SealedBox.open(recipient, sealed, associatedData) }
        assertEquals(SealedBoxException.Reason.UNSUPPORTED_VERSION, failure.reason)
    }

    @Test
    fun aFailureCarriesNoPlaintextAndNoCause() = runTest {
        val recipient = SealPrivateKey.generate()
        val secret = "SECRET-HAND-R1-B5".encodeToByteArray()
        val sealed = SealedBox.seal(recipient.publicKey, secret, associatedData)
        val failure = assertFailsWith<SealedBoxException> {
            SealedBox.open(SealPrivateKey.generate(), sealed, associatedData)
        }
        val message = failure.message.orEmpty()
        assertAll(
            { assertFalse(message.contains("SECRET"), "message carried plaintext: $message") },
            { assertFalse(message.contains(secret.toHexString(), ignoreCase = true), "message carried plaintext hex") },
            { assertNull(failure.cause, "a provider cause would carry a message this module does not control") },
        )
    }

    @Test
    fun aPublicKeyEncodesAsTheSixtyFiveByteUncompressedPoint() = runTest {
        val key = SealPrivateKey.generate().publicKey
        val encoded = key.encoded()
        val decoded = SealPublicKey.decode(encoded)
        assertAll(
            { assertEquals(SealPublicKey.ENCODED_SIZE, encoded.size) },
            { assertEquals(0x04.toByte(), encoded[0]) },
            { assertEquals(key, decoded) },
            { assertEquals(key.hashCode(), decoded.hashCode()) },
        )
    }

    @Test
    fun encodedReturnsACopy() = runTest {
        val key = SealPrivateKey.generate().publicKey
        key.encoded()[1] = (key.encoded()[1].toInt() xor 0xFF).toByte()
        assertEquals(key, SealPublicKey.decode(key.encoded()))
    }

    @Test
    fun distinctKeysAreUnequal() = runTest {
        assertNotEquals(SealPrivateKey.generate().publicKey, SealPrivateKey.generate().publicKey)
    }

    @Test
    fun encodePkcs8RoundTripsAndTheRestoredKeyOpens() = runTest {
        val original = SealPrivateKey.generate()
        val sealed = SealedBox.seal(original.publicKey, plaintext, associatedData)
        val der = original.encodePkcs8()
        val restored = SealPrivateKey.decodePkcs8(der)
        val opened = SealedBox.open(restored, sealed, associatedData)
        assertAll(
            // The written file carries the public point itself, whatever the provider's export did.
            { assertTrue(der.containsSubarray(original.publicKey.encoded()), "PKCS #8 without its public key") },
            { assertEquals(original.publicKey, restored.publicKey) },
            { assertContentEquals(plaintext, opened) },
        )
    }

    @Test
    fun aPkcs8WhosePublicKeyBelongsToAnotherKeyIsRefused() = runTest {
        // Hand-built: the KAT recipient's PKCS #8 with its embedded public point swapped for another
        // valid P-256 point. OpenSSL refuses it (`pkey -check`: "ossl_ec_key_pairwise_check"); SunEC
        // alone would take the embedded point on trust.
        val failure = decodePkcs8Failure(MISMATCHED_PKCS8.hexToByteArray())
        assertTrue(failure is IllegalArgumentException, "a mismatched key pair gave $failure")
    }

    @Test
    fun aPkcs8WrittenByEncodePkcs8IsPairChecked() = runTest {
        // The same swap applied to a file encodePkcs8 wrote: the check covers this module's own
        // output, not only foreign files.
        val der = SealPrivateKey.generate().encodePkcs8()
        val swapped = pkcs8WithPublicKey(der, SealPrivateKey.generate().publicKey.encoded())
        val failure = decodePkcs8Failure(swapped)
        assertTrue(failure is IllegalArgumentException, "a mismatched key pair gave $failure")
    }

    @Test
    fun aSealToADecodedPublicKeyOpensWithTheOriginalPrivateKey() = runTest {
        val recipient = SealPrivateKey.generate()
        val published = SealPublicKey.decode(recipient.publicKey.encoded())
        val sealed = SealedBox.seal(published, plaintext, associatedData)
        assertContentEquals(plaintext, SealedBox.open(recipient, sealed, associatedData))
    }

    @Test
    fun badPublicKeyBytesAreRejected() = runTest {
        val notOnCurve = ByteArray(SealPublicKey.ENCODED_SIZE).also { it[0] = 0x04; it[64] = 7 }
        val wrongLength = decodeFailure(ByteArray(64))
        val offCurve = decodeFailure(notOnCurve)
        assertAll(
            { assertTrue(wrongLength is IllegalArgumentException, "wrong length gave $wrongLength") },
            { assertTrue(offCurve is IllegalArgumentException, "off-curve point gave $offCurve") },
        )
    }

    @Test
    fun badPrivateKeyBytesAreRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { SealPrivateKey.decodePkcs8(ByteArray(40) { 3 }) }
    }

    @Test
    fun privateKeyToStringPrintsOnlyThePublicFingerprint() = runTest {
        val key = SealPrivateKey.generate()
        // Exact, so nothing beyond the public fingerprint can ride along.
        assertAll(
            { assertEquals("SealPrivateKey(publicKey=${key.publicKey})", key.toString()) },
            { assertTrue(key.publicKey.toString().matches(Regex("""SealPublicKey\(04[0-9a-f]{14}…\)"""))) },
        )
    }

    @Test
    fun sealRefusesAReplacedRandomSource() = runTest {
        // Through seal itself, not the guard alone, so deleting the guard's call from seal reds this.
        val recipient = SealPrivateKey.generate()
        val seeded = object : CryptographyRandom() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        val withPlatformRandom = SealedBox.seal(recipient.publicKey, plaintext, associatedData, CryptographyRandom.Default)
        val opened = SealedBox.open(recipient, withPlatformRandom, associatedData)
        val refusal = try {
            SealedBox.seal(recipient.publicKey, plaintext, associatedData, seeded)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            e
        }
        assertAll(
            { assertContentEquals(plaintext, opened) },
            { assertTrue(refusal?.message.orEmpty().contains("setDefaultRandom"), "seal accepted a seeded random: $refusal") },
        )
    }

    /** What [SealPrivateKey.decodePkcs8] threw for [der], or null if it returned. */
    private suspend fun decodePkcs8Failure(der: ByteArray): Throwable? = try {
        SealPrivateKey.decodePkcs8(der)
        null
    } catch (e: IllegalArgumentException) {
        e
    }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private companion object {
        /** SealedBoxKnownAnswerTest's recipient PKCS #8, its public point replaced by an unrelated one. */
        const val MISMATCHED_PKCS8 =
            "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ed43106c5d978b86" +
                "a5a39d1ce543a0980ce60b9bd43727e506f5c68616046f45a14403420004269ecfd436d7baf0a41475a681" +
                "325c137b2b52862c0836cf09fb245acb84eee5c3e935a011959d9f995a259f8bb1a3912fed7f13682df874" +
                "c406eb38b3b77500"
    }

    /** What [SealPublicKey.decode] threw for [bytes], or null if it returned. */
    private suspend fun decodeFailure(bytes: ByteArray): Throwable? = try {
        SealPublicKey.decode(bytes)
        null
    } catch (e: IllegalArgumentException) {
        e
    }

    private fun ByteArray.ephemeralKey(): ByteArray = copyOfRange(1, 1 + SealPublicKey.ENCODED_SIZE)

    private fun ByteArray.nonce(): ByteArray = copyOfRange(1 + SealPublicKey.ENCODED_SIZE, 1 + SealPublicKey.ENCODED_SIZE + 12)
}
