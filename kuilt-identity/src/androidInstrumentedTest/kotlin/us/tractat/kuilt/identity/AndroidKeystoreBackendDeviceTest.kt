package us.tractat.kuilt.identity

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

/**
 * [AndroidKeystoreBackend] against the device's real `AndroidKeyStore` provider (#3226): that the
 * Keystore accepts [keyGenSpec], and that each member answers as [KeystoreBackend]'s KDoc says, which
 * the store's unit tests can only assume through `FakeKeystore`.
 */
class AndroidKeystoreBackendDeviceTest {
    private val alias = "kuilt-identity-device-test-${UUID.randomUUID()}"

    @AfterTest
    fun deleteTheKey() {
        KeystoreFaults.deleteTolerantly(alias)
    }

    /**
     * The Keystore accepts the spec as written and makes the key the store asks for: EC P-256, for
     * signing alone with SHA-256, needing no user authentication. Its signature passes the store's own
     * self-test under the name its certificate gives.
     */
    @Test
    fun aGeneratedKeyIsTheKeyTheSpecAsksForAndSignsUnderItsName() = runTest {
        AndroidKeystoreBackend.generate(alias)
        val signer = assertNotNull(AndroidKeystoreBackend.getKey(alias), "the key just made comes back")
        val publicKey = assertNotNull(AndroidKeystoreBackend.getCertificatePublicKey(alias), "and its certificate")
        val read = readBack(publicKey, signer, SelfTestVerifier.Provider)
        val info = keyInfo(alias)
        Log.i(TAG, "API ${Build.VERSION.SDK_INT}: key under $alias is ${securityLevel(info)}")
        assertAll(
            { assertIs<KeystoreRead.Usable>(read, "the key passes the store's self-test: $read") },
            { assertEquals(256, (publicKey as ECPublicKey).params.curve.field.fieldSize, "a P-256 public half") },
            { assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes, "for signing alone") },
            { assertTrue(info.digests.contentEquals(arrayOf(KeyProperties.DIGEST_SHA256)), "with SHA-256 alone: ${info.digests.toList()}") },
            { assertFalse(info.isUserAuthenticationRequired, "with no user authentication") },
        )
    }

    /** An alias nothing was made under is "no key" and "no certificate" — answered, not thrown. */
    @Test
    fun anAliasNothingWasMadeUnderIsNoKeyAndNoCertificate() {
        assertAll(
            { assertNull(AndroidKeystoreBackend.getKey(alias), "no key") },
            { assertNull(AndroidKeystoreBackend.getCertificatePublicKey(alias), "no certificate") },
        )
    }

    /** [KeystoreBackend.delete] removes the key and its certificate, and deleting nothing is no error. */
    @Test
    fun aDeletedKeyIsGone() {
        AndroidKeystoreBackend.generate(alias)
        AndroidKeystoreBackend.delete(alias)
        AndroidKeystoreBackend.delete(alias)
        assertAll(
            { assertNull(AndroidKeystoreBackend.getKey(alias), "no key") },
            { assertNull(AndroidKeystoreBackend.getCertificatePublicKey(alias), "no certificate") },
        )
    }

    /** [KeystoreBackend.generate] over a held key replaces it, as its KDoc says. */
    @Test
    fun generatingOverAHeldKeyReplacesIt() {
        AndroidKeystoreBackend.generate(alias)
        val first = assertNotNull(AndroidKeystoreBackend.getCertificatePublicKey(alias))
        AndroidKeystoreBackend.generate(alias)
        val second = assertNotNull(AndroidKeystoreBackend.getCertificatePublicKey(alias))
        assertNotEquals(first, second, "a second generate makes a new key under the alias")
    }

    private fun keyInfo(alias: String): KeyInfo {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as PrivateKey
        return KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
    }

    @Suppress("DEPRECATION") // isInsideSecureHardware is the only answer below API 31.
    private fun securityLevel(info: KeyInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "securityLevel=${info.securityLevel}" else "insideSecureHardware=${info.isInsideSecureHardware}"

    private companion object {
        const val TAG = "kuilt-identity-device"
    }
}
