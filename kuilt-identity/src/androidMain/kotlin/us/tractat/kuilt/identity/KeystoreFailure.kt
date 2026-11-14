package us.tractat.kuilt.identity

import android.annotation.SuppressLint
import android.security.KeyStoreException
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException

/**
 * Something that stopped the store reading its key: an exception from loading or signing, a
 * certificate that did not come back for a key that did, or no key where one was made.
 */
internal sealed interface KeystoreFailure {
    /** What the warning says it was, in words that carry no key material. */
    val description: String

    /** The Keystore threw [error] while the store [doing] — "loading the key", "signing its self-test". */
    class Thrown(val doing: String, val error: Throwable) : KeystoreFailure {
        override val description: String get() = "${error::class.simpleName} while $doing"
    }

    /**
     * The key came back and its certificate did not. `getCertificate` returns `null` on a Keystore
     * failure as well as on a missing certificate, so this is never proof the certificate is gone.
     */
    object NoCertificate : KeystoreFailure {
        override val description: String = "the key came back and its certificate did not"
    }

    /**
     * `getKey` answered "no key" where this store has made one (its marker is beside the grants). On
     * Android 11 and earlier that is how a Keystore that did not answer looks, so it is never proof the
     * key is gone.
     */
    object NoKeyThoughOneWasMade : KeystoreFailure {
        override val description: String = "the Keystore answered no key, though one was made here"
    }
}

/** What the store does about a [KeystoreFailure]. */
internal sealed interface FailureVerdict {
    /** The key is damaged for good: delete it and make a new one. */
    class Replace(val why: String) : FailureVerdict

    /**
     * The Keystore says the failure is its own, not the key's: throw, keep the key, and count nothing,
     * so however long the Keystore stays down it cannot cost the device its key.
     */
    object Keep : FailureVerdict

    /**
     * Nothing says whose failure it is: throw, count a failed launch, and replace the key once
     * [FAILED_LAUNCHES_BEFORE_REPLACING] consecutive launches have failed (Decided 29).
     */
    object Count : FailureVerdict
}

/** Consecutive failed launches before a key Android cannot judge is replaced (Decided 29). */
internal const val FAILED_LAUNCHES_BEFORE_REPLACING: Int = 3

/** The first Android version whose `android.security.KeyStoreException` says what kind of failure it was. */
internal const val KEYSTORE_EXCEPTION_API: Int = 33

/**
 * The public `KeyStoreException` codes that name the key rather than the Keystore: it does not exist,
 * it is corrupted, or KeyMint refused it without calling the refusal a system error — where
 * `KM_ERROR_INVALID_KEY_BLOB` lands (as `ERROR_KEYMINT_FAILURE`, 10, with no flags).
 */
private val KEY_SPECIFIC_CODES = setOf(
    6, // ERROR_KEY_DOES_NOT_EXIST: KEY_NOT_FOUND, KEY_PERMANENTLY_INVALIDATED
    7, // ERROR_KEY_CORRUPTED: VALUE_CORRUPTED
    10, // ERROR_KEYMINT_FAILURE, when not flagged a system error: INVALID_KEY_BLOB among others
)

/**
 * Decided 29 (amended), as AOSP's Keystore actually reports failures (read from source, not measured on
 * a device):
 *
 * - **`KeyPermanentlyInvalidatedException`, on any version:** the Keystore's own verdict that the key
 *   will never sign again. [FailureVerdict.Replace].
 * - **Android 13 and later ([sdkInt] ≥ 33):** the `android.security.KeyStoreException` in the cause chain
 *   decides.
 *   - Transient, a system error, or a wait for the user: the Keystore's own trouble, so
 *     [FailureVerdict.Keep] — and *not* counted. Counting these would let a Keystore outage that outlasts
 *     three launches cost the device its key, which is the one thing this rule exists to prevent. The
 *     price: a key that fails forever with a system-error code (`KM_ERROR_UNKNOWN_ERROR`, say) is never
 *     replaced, and the device throws on every launch until its data is cleared.
 *   - A key-specific code ([KEY_SPECIFIC_CODES]): the key's, so [FailureVerdict.Replace].
 *   - Any other code: [FailureVerdict.Count].
 *   - No such cause: an `UnrecoverableKeyException` is a verdict on the stored key (a permanently
 *     invalidated key at load, an EC key with no certificate) and replaces; anything else counts.
 *   - A missing certificate, or a missing key that was made here: counts. keystore2 cannot hold an EC
 *     key without its certificate, so it is a read that failed; counting rather than keeping means a
 *     read that keeps failing cannot strand the device.
 * - **Below 33:** no public API says which kind a failure is, so every failure [FailureVerdict.Count]s.
 *
 * Why not `isTransientFailure()` alone, as Decided 29 words it: it is `true` for only nine codes, and
 * `false` for `SYSTEM_ERROR`, `UNINITIALIZED` and `KM_ERROR_UNKNOWN_ERROR` — the codes a Keystore that
 * is merely failing right now answers with. Reading "not transient" as "permanent" would replace the key
 * over exactly the passing fault this store must survive.
 */
@SuppressLint("NewApi") // Every KeyStoreException call is behind sdkInt >= KEYSTORE_EXCEPTION_API, which lint cannot see.
internal fun judge(failure: KeystoreFailure, sdkInt: Int): FailureVerdict {
    if (failure is KeystoreFailure.Thrown && failure.error is KeyPermanentlyInvalidatedException) {
        return FailureVerdict.Replace("the Keystore reports it permanently invalidated")
    }
    if (sdkInt < KEYSTORE_EXCEPTION_API) return FailureVerdict.Count
    val error = (failure as? KeystoreFailure.Thrown)?.error ?: return FailureVerdict.Count
    val keystore = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<KeyStoreException>().firstOrNull()
    return when {
        keystore == null && error is UnrecoverableKeyException ->
            FailureVerdict.Replace("the Keystore cannot recover it (UnrecoverableKeyException)")
        keystore == null -> FailureVerdict.Count
        keystore.isTransientFailure || keystore.isSystemError || keystore.requiresUserAuthentication() -> FailureVerdict.Keep
        keystore.numericErrorCode in KEY_SPECIFIC_CODES ->
            FailureVerdict.Replace("the Keystore reports it damaged (KeyStoreException code ${keystore.numericErrorCode})")
        else -> FailureVerdict.Count
    }
}

/** How far down a cause chain [judge] looks; a chain is two or three links in practice. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * The failed-launch count kept beside the grants: how many consecutive launches failed to read the key,
 * and which launch was counted last, so a launch that asks many times counts once.
 */
internal class FailedLaunches(val count: Int, val lastLaunch: String) {
    fun encode(): ByteArray = byteArrayOf(VERSION, count.coerceIn(0, Byte.MAX_VALUE.toInt()).toByte()) + lastLaunch.encodeToByteArray()

    companion object {
        private const val VERSION: Byte = 1

        /** The record in [bytes], or `null` when it is not one [encode] wrote, which counts as none. */
        fun decode(bytes: ByteArray): FailedLaunches? {
            if (bytes.size < 2 || bytes[0] != VERSION || bytes[1] < 0) return null
            return FailedLaunches(bytes[1].toInt(), bytes.copyOfRange(2, bytes.size).decodeToString())
        }
    }
}
