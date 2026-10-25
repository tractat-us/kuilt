package us.tractat.kuilt.seal

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A version-1 box built WITHOUT cryptography-kotlin, opened on every target.
 *
 * The vector was produced by OpenSSL 3.6 (P-256 key generation, ECDH via `pkeyutl -derive`,
 * HKDF-SHA-256 via `openssl kdf`) and plain JCA `AES/GCM/NoPadding` for the AEAD, following the
 * format in [SealedBox]'s KDoc byte for byte. Opening it pins the wire format and the key schedule
 * against an independent implementation, which a round trip through one provider cannot: a seal and
 * an open that agree on a wrong label, salt or layout still round-trip.
 *
 * The recipient key is a throwaway made for this test and protects nothing.
 */
class SealedBoxKnownAnswerTest {
    @Test
    fun anIndependentlyBuiltBoxOpens() = runTest {
        val recipient = SealPrivateKey.decodePkcs8(RECIPIENT_PKCS8.hexToByteArray())
        val decodedPublic = SealPublicKey.decode(RECIPIENT_PUBLIC.hexToByteArray())
        val opened = SealedBox.open(recipient, SEALED.hexToByteArray(), ASSOCIATED_DATA.hexToByteArray())
        assertAll(
            { assertContentEquals(RECIPIENT_PUBLIC.hexToByteArray(), recipient.publicKey.encoded()) },
            { assertEquals(decodedPublic, recipient.publicKey) },
            { assertContentEquals(PLAINTEXT.hexToByteArray(), opened) },
        )
    }

    @Test
    fun theIndependentBoxRefusesOtherAssociatedData() = runTest {
        val recipient = SealPrivateKey.decodePkcs8(RECIPIENT_PKCS8.hexToByteArray())
        val failure = assertFailsWith<SealedBoxException> {
            SealedBox.open(recipient, SEALED.hexToByteArray(), "problem-report:v2".encodeToByteArray())
        }
        assertEquals(SealedBoxException.Reason.NOT_AUTHENTIC, failure.reason)
    }

    private companion object {
        const val RECIPIENT_PKCS8 =
            "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ed43106c5d978b86" +
                "a5a39d1ce543a0980ce60b9bd43727e506f5c68616046f45a1440342000423161b652dafc4beef3f403b0a" +
                "d40a860c2cb2752ccd75f801c9e31de50b1819d77a69cff62484f59478d688a83f0ec6c6b2f32823e195f9" +
                "b573a67883424396"
        const val RECIPIENT_PUBLIC =
            "0423161b652dafc4beef3f403b0ad40a860c2cb2752ccd75f801c9e31de50b1819d77a69cff62484f59478d6" +
                "88a83f0ec6c6b2f32823e195f9b573a67883424396"

        // "problem-report:v1"
        const val ASSOCIATED_DATA = "70726f626c656d2d7265706f72743a7631"

        // "the position, padded by the caller"
        const val PLAINTEXT = "74686520706f736974696f6e2c20706164646564206279207468652063616c6c6572"
        const val SEALED =
            "010491405c72c0c8e372e9e4db51ea1baa1ce14529b2961aa30107e1b0c2ff5917e6ec76181a720ea9e8344b" +
                "e26d632c67dfca2d8b52debfc9e3ced15e0f0f53260afc98d9a50117d1f5500fe06e6eeaddc18a2eac774e" +
                "753240c3786d1b203e24eb529cfb0babea576979e96be5baa4a52666a4da9e1c0720543c616ba61b8b"
    }
}
