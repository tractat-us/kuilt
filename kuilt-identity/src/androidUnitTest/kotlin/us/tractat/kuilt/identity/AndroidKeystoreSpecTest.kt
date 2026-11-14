package us.tractat.kuilt.identity

import android.security.keystore.KeyProperties
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import us.tractat.kuilt.test.assertAll
import java.io.File
import java.security.Key
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the store asks the Keystore for, read off the real framework classes under Robolectric — the
 * spec it builds and the directory the one-line default picks. Robolectric has no `AndroidKeyStore`
 * provider, so nothing here makes a key: whether a real Keystore honours this spec is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidKeystoreSpecTest {

    /**
     * The key is P-256, for signing alone, over SHA-256, under the alias it was asked for, and made by
     * the `AndroidKeyStore` provider — so its private half never exists outside the Keystore. Nothing
     * the store reaches can export it either: no member of the backend seam returns a key object or key
     * bytes, only a public key from the certificate and DER signatures.
     */
    @Test
    fun keystoreSpecIsSignOnlyAndDeviceBound() {
        val spec = keyGenSpec("kuilt-identity-my-app")
        val curve = spec.algorithmParameterSpec as? ECGenParameterSpec

        val seam = listOf(KeystoreBackend::class.java, KeystoreSigner::class.java)
        val exporting = seam.flatMap { it.declaredMethods.toList() }.filter { method ->
            val returns = method.returnType
            (Key::class.java.isAssignableFrom(returns) && returns != java.security.PublicKey::class.java) ||
                (returns == ByteArray::class.java && method.name != "signDer")
        }

        assertAll(
            { assertEquals("AndroidKeyStore", ANDROID_KEYSTORE, "made by the Android Keystore provider") },
            { assertEquals("kuilt-identity-my-app", spec.keystoreAlias, "under the alias asked for") },
            { assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes, "for signing, and nothing else") },
            { assertContentEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests, "over SHA-256 alone") },
            { assertEquals("secp256r1", curve?.name, "on P-256") },
            { assertFalse(spec.isUserAuthenticationRequired, "signing never waits for the person") },
            { assertFalse(spec.isUnlockedDeviceRequired, "nor for an unlocked screen, so a background reconnect can sign") },
            { assertEquals(emptyList(), exporting.map { it.toString() }, "no member of the seam can hand out key material") },
        )
    }

    /** The one-line default keeps the grants in the app's no-backup directory, and makes no Keystore call to open. */
    @Test
    fun platformKeepsGrantsInNoBackupFilesDir() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = DeviceKeyStore.platform("my-app", context)
        val grants = store.grants()
        val directory = File(context.noBackupFilesDir, "kuilt-identity-my-app")
        assertAll(
            { assertEquals(emptyList(), grants, "a new store holds no grants") },
            { assertTrue(directory.isDirectory, "and its directory is under noBackupFilesDir: ${context.noBackupFilesDir}") },
        )
    }
}
