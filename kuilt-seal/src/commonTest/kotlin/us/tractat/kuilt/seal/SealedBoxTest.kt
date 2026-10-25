package us.tractat.kuilt.seal

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
        assertAll(
            { assertContentEquals(plaintext, SealedBox.open(recipient, sealed, associatedData)) },
            { assertEquals(plaintext.size + SealedBox.OVERHEAD, sealed.size) },
        )
    }

    @Test
    fun emptyPlaintextAndEmptyAssociatedDataRoundTrip() = runTest {
        val recipient = SealPrivateKey.generate()
        val sealed = SealedBox.seal(recipient.publicKey, ByteArray(0), ByteArray(0))
        assertAll(
            { assertContentEquals(ByteArray(0), SealedBox.open(recipient, sealed, ByteArray(0))) },
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
        assertAll(
            { assertEquals(SealPublicKey.ENCODED_SIZE, encoded.size) },
            { assertEquals(0x04.toByte(), encoded[0]) },
            { assertEquals(key, SealPublicKey.decode(encoded)) },
            { assertEquals(key.hashCode(), SealPublicKey.decode(encoded).hashCode()) },
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
    fun aPrivateKeySurvivesPkcs8AndStillOpens() = runTest {
        val original = SealPrivateKey.generate()
        val sealed = SealedBox.seal(original.publicKey, plaintext, associatedData)
        val restored = SealPrivateKey.decodePkcs8(original.encodePkcs8())
        assertAll(
            { assertEquals(original.publicKey, restored.publicKey) },
            { assertContentEquals(plaintext, SealedBox.open(restored, sealed, associatedData)) },
        )
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
        assertAll(
            { assertFailsWith<IllegalArgumentException> { SealPublicKey.decode(ByteArray(64)) } },
            { assertFailsWith<IllegalArgumentException> { SealPublicKey.decode(notOnCurve) } },
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
    fun sealingRefusesAReplacedRandomSource() {
        val seeded = object : CryptographyRandom() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        assertAll(
            { requirePlatformRandom(CryptographyRandom.Default) },
            { assertFailsWith<IllegalStateException> { requirePlatformRandom(seeded) } },
        )
    }

    private fun ByteArray.ephemeralKey(): ByteArray = copyOfRange(1, 1 + SealPublicKey.ENCODED_SIZE)

    private fun ByteArray.nonce(): ByteArray = copyOfRange(1 + SealPublicKey.ENCODED_SIZE, 1 + SealPublicKey.ENCODED_SIZE + 12)
}
