package us.tractat.kuilt.identity

import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * A [KeystoreBackend] over JCA's software EC keys that answers the way AOSP's `AndroidKeyStore` does,
 * including where it hides a failure, so a test can leave every state a real Keystore can be found in.
 *
 * Holds each alias's two halves apart, as the Keystore does: a private key and a certificate's public
 * key, either of which may be absent. Faults follow AOSP (read from source, measured on no device):
 *
 * - **[loadFault]** is thrown by [getKey], and while it is set [getCertificatePublicKey] answers `null`
 *   — keystore2's `getCertificate` catches its error and returns `null`, it never throws.
 * - **[certificateLost]** makes [getCertificatePublicKey] alone answer `null` while [getKey] succeeds:
 *   a certificate read that failed after the key read did not.
 * - **[signFault]** is thrown by every signature, as a Keystore failing mid-operation; [damage] makes one
 *   alias's signatures throw for good, as a damaged key does.
 * - **[legacyOutage]** makes [getKey] and [getCertificatePublicKey] answer `null`, as Android 11 and
 *   earlier do when the Keystore fails: their "is it there?" check answers "no" on an error.
 * - **[generateFault]** is thrown once by the next [generate], before anything is stored: a key that was
 *   never made, as a crash or a failure mid-generation leaves.
 *
 * What it cannot be: the Keystore. It signs in the JVM's own provider, it makes keys the JVM's way rather
 * than from a [android.security.keystore.KeyGenParameterSpec], and the exception each fault carries is
 * AOSP's code as read, not as observed.
 */
internal class FakeKeystore : KeystoreBackend {
    private class Held(val publicKey: PublicKey?, val privateKey: PrivateKey?, val damage: (() -> Exception)? = null)

    private val held = ConcurrentHashMap<String, Held>()

    @Volatile
    var loadFault: (() -> Exception)? = null

    @Volatile
    var certificateLost: Boolean = false

    @Volatile
    var signFault: (() -> Exception)? = null

    @Volatile
    var legacyOutage: Boolean = false

    @Volatile
    var generateFault: (() -> Exception)? = null

    /** How many times [generate] ran. */
    @Volatile
    var generated: Int = 0
        private set

    override fun getKey(alias: String): KeystoreSigner? {
        loadFault?.let { throw it() }
        if (legacyOutage) return null
        val entry = held[alias] ?: return null
        val key = entry.privateKey ?: return null
        return KeystoreSigner { message ->
            signFault?.let { throw it() }
            entry.damage?.let { throw it() }
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(key)
                update(message)
                sign()
            }
        }
    }

    override fun getCertificatePublicKey(alias: String): PublicKey? =
        if (loadFault != null || certificateLost || legacyOutage) null else held[alias]?.publicKey

    override fun generate(alias: String) {
        generateFault?.let { fault ->
            generateFault = null
            throw fault()
        }
        generated++
        val pair = newPair()
        held[alias] = Held(pair.public, pair.private)
    }

    override fun delete(alias: String) {
        held.remove(alias)
    }

    fun holds(alias: String): Boolean = held.containsKey(alias)

    /** Holds [publicKey] and [privateKey] under [alias], either possibly `null`, whatever they are. */
    fun plant(alias: String, publicKey: PublicKey?, privateKey: PrivateKey?) {
        held[alias] = Held(publicKey, privateKey)
    }

    /** The key under [alias] now answers every signature by throwing [fault]'s exception. */
    fun damage(alias: String, fault: () -> Exception) {
        val entry = checkNotNull(held[alias]) { "precondition: a key under $alias to damage" }
        held[alias] = Held(entry.publicKey, entry.privateKey, fault)
    }

    companion object {
        fun newPair(curve: String = "secp256r1"): KeyPair =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec(curve))
                generateKeyPair()
            }
    }
}

/**
 * AOSP's own Keystore exceptions, built the way the framework builds them, for tests under Robolectric
 * (whose framework classes are real). `android.security.KeyStoreException`'s constructor is hidden, so
 * it is reached by reflection; the codes are read from the framework's own constants by name, never
 * typed in, so a renumbered constant fails here rather than silently testing another code.
 */
internal object AospKeystore {
    /** `ResponseCode.SYSTEM_ERROR`: what a Keystore that is merely failing right now answers. */
    val SYSTEM_ERROR: Int get() = constant("android.system.keystore2.ResponseCode", "SYSTEM_ERROR")

    /** `KM_ERROR_INVALID_KEY_BLOB`: the key's stored blob cannot be used. */
    val INVALID_KEY_BLOB: Int get() = constant("android.security.keymaster.KeymasterDefs", "KM_ERROR_INVALID_KEY_BLOB")

    /** `KM_ERROR_UNKNOWN_ERROR`: the same public code as a bad blob, flagged a system error. */
    val UNKNOWN_ERROR: Int get() = constant("android.security.keymaster.KeymasterDefs", "KM_ERROR_UNKNOWN_ERROR")

    /** `KM_ERROR_SECURE_HW_BUSY`: one of the nine codes the platform calls transient. */
    val SECURE_HW_BUSY: Int get() = constant("android.security.keymaster.KeymasterDefs", "KM_ERROR_SECURE_HW_BUSY")

    fun keystoreException(code: Int): android.security.KeyStoreException =
        android.security.KeyStoreException::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .newInstance(code, "test: Keystore code $code")

    /** What keystore2's `getKey` throws for any failure but `KEY_NOT_FOUND`. */
    fun loadFailure(code: Int): UnrecoverableKeyException =
        UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(keystoreException(code)) }

    /** What keystore2 throws from `initSign` for any code without a mapping of its own. */
    fun signFailure(code: Int): InvalidKeyException = InvalidKeyException("Keystore operation failed", keystoreException(code))

    private fun constant(className: String, field: String): Int = Class.forName(className).getField(field).getInt(null)
}
