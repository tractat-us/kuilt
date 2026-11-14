package us.tractat.kuilt.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec

/**
 * The Android Keystore, one member per JCA call the store makes and nothing more, so every decision
 * about what a Keystore answer *means* lives in the store, where the tests reach it. A JVM unit test
 * has no `AndroidKeyStore` provider, so this is the seam a fake stands in at.
 *
 * Each member answers exactly as its JCA call does, including where that call hides a failure. The
 * store's correctness rests on knowing which those are (read from AOSP, not measured on a device):
 *
 * - [getKey] is `KeyStore.getKey(alias, null)`. On Android 12 and later (keystore2) it returns `null`
 *   only when the Keystore answered `KEY_NOT_FOUND`, and throws `UnrecoverableKeyException` for every
 *   other failure, with the `android.security.KeyStoreException` as its cause — except a key the
 *   Keystore calls permanently invalidated, rethrown with no cause, and an EC key with no certificate,
 *   also no cause. **On Android 11 and earlier it returns `null` when the Keystore fails too**: it first
 *   asks a yes/no "is it there?", which answers "no" on an error. No JCA call there tells an absent key
 *   from a Keystore that did not answer.
 * - [getCertificatePublicKey] is `KeyStore.getCertificate(alias)?.publicKey`, which returns `null` both
 *   when there is no certificate and when the Keystore fails: keystore2 catches every error and returns
 *   `null`. So a `null` here never proves the certificate is gone.
 *
 * Deliberately without any member that returns a private key or its bytes: the store reaches the key
 * only as a [KeystoreSigner], so nothing it holds can export the key.
 */
internal interface KeystoreBackend {
    /** `KeyStore.getKey(alias, null)`, as a signer over it; see [KeystoreBackend] for what `null` means. */
    fun getKey(alias: String): KeystoreSigner?

    /** `KeyStore.getCertificate(alias)?.publicKey`: `null` when absent *or* when the Keystore failed. */
    fun getCertificatePublicKey(alias: String): PublicKey?

    /** Makes a new key under [alias] from [keyGenSpec], replacing anything held there. */
    fun generate(alias: String)

    /** `KeyStore.deleteEntry(alias)`. */
    fun delete(alias: String)
}

/** Signs with a key the Keystore holds: ECDSA over SHA-256, as the DER `ECDSA-Sig-Value` JCA returns. */
internal fun interface KeystoreSigner {
    fun signDer(message: ByteArray): ByteArray
}

/** The JCA provider name of the Android Keystore. */
internal const val ANDROID_KEYSTORE: String = "AndroidKeyStore"

/** The JCA signature the Keystore key signs with. */
internal const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"

private const val P256_CURVE_NAME = "secp256r1"

/**
 * What a device key is made from: EC P-256, for signing alone, with SHA-256. Nothing here asks for
 * user authentication, an unlocked screen or StrongBox: a key that could not sign while the phone is
 * locked could not prove a device that reconnects in the background, and StrongBox is absent on many
 * phones.
 */
internal fun keyGenSpec(alias: String): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE_NAME))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .build()

/** The real Android Keystore. Loaded on first use, so opening a store touches nothing. */
internal object AndroidKeystoreBackend : KeystoreBackend {
    // A failed load is not cached: `lazy` runs its initializer again on the next access.
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun getKey(alias: String): KeystoreSigner? {
        val key = keyStore.getKey(alias, null) ?: return null
        // Not reachable through this store, which makes only EC private keys under its alias; a key of
        // another kind there is the alias's contents, so it is reported the way the Keystore reports
        // a key it cannot use as one: an UnrecoverableKeyException with no Keystore cause.
        val privateKey = key as? PrivateKey ?: throw UnrecoverableKeyException("not a private key (${key.algorithm})")
        return KeystoreSigner { message ->
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(privateKey)
                update(message)
                sign()
            }
        }
    }

    override fun getCertificatePublicKey(alias: String): PublicKey? = keyStore.getCertificate(alias)?.publicKey

    override fun generate(alias: String) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(keyGenSpec(alias))
            generateKeyPair()
        }
    }

    override fun delete(alias: String) {
        keyStore.deleteEntry(alias)
    }
}
