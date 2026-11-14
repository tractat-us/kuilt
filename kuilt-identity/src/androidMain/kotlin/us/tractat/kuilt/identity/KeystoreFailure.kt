package us.tractat.kuilt.identity

import android.annotation.SuppressLint
import android.security.KeyStoreException
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import kotlin.time.Duration.Companion.hours

/**
 * Something that stopped the store reading its key: an exception from loading or signing, a
 * certificate that did not come back for a key that did, or no key where one was made.
 */
internal sealed interface KeystoreFailure {
    /** What the warning says it was, in words that carry no key material. */
    val description: String

    /** The throwable behind it, if there was one: the warning logs it, Keystore code and all. */
    val cause: Throwable?

    /** The Keystore threw [error] while the store [doing] — "loading the key", "signing its self-test". */
    class Thrown(val doing: String, val error: Throwable) : KeystoreFailure {
        override val description: String get() = "${error::class.simpleName} while $doing"
        override val cause: Throwable get() = error
    }

    /**
     * The key came back and its certificate did not. `getCertificate` returns `null` on a Keystore
     * failure as well as on a missing certificate, so this is never proof the certificate is gone.
     */
    object NoCertificate : KeystoreFailure {
        override val description: String = "the key came back and its certificate did not"
        override val cause: Throwable? = null
    }

    /**
     * `getKey` answered "no key" where this store has made one (its marker is beside the grants). On
     * Android 11 and earlier that is how a Keystore that did not answer looks, so it is never proof the
     * key is gone.
     */
    object NoKeyThoughOneWasMade : KeystoreFailure {
        override val description: String = "the Keystore answered no key, though one was made here"
        override val cause: Throwable? = null
    }
}

/** What the store does about a [KeystoreFailure]. */
internal sealed interface FailureVerdict {
    /** The key is damaged for good: delete it and make a new one. */
    class Replace(val why: String) : FailureVerdict

    /** The Keystore says the failure is its own, not the key's: throw and keep the key, however long it lasts. */
    object Keep : FailureVerdict

    /**
     * Nothing says whose failure it is: throw and keep the key, unless the Keystore has been failing for
     * [UNKNOWN_CAUSE_PATIENCE] with no successful read in between — then replace it (Decided 29).
     */
    object Unknown : FailureVerdict
}

/** How long a run of failures of unknown cause lasts before the key is replaced (Decided 29). */
internal val UNKNOWN_CAUSE_PATIENCE = 1.hours

/** The first Android version whose `android.security.KeyStoreException` says what kind of failure it was. */
internal const val KEYSTORE_EXCEPTION_API: Int = 33

/**
 * The public `KeyStoreException` codes that blame the key: `ERROR_KEY_DOES_NOT_EXIST` (6, from
 * `KEY_NOT_FOUND` and `KEY_PERMANENTLY_INVALIDATED`) and `ERROR_KEY_CORRUPTED` (7, from `VALUE_CORRUPTED`).
 * Not `ERROR_KEYMINT_FAILURE` (10): AOSP puts `INVALID_KEY_BLOB` there beside passing TEE faults
 * (`MEMORY_ALLOCATION_FAILED`, `TOO_MANY_OPERATIONS`, `HARDWARE_NOT_YET_AVAILABLE`, vendor codes), so
 * it says nothing about whose failure it was.
 */
private val KEY_SPECIFIC_CODES = setOf(6, 7)

/**
 * Decided 29, as AOSP's Keystore reports failures (read from source, not measured on a device):
 *
 * - **`KeyPermanentlyInvalidatedException`, on any version:** the Keystore's verdict that the key will
 *   never sign again. [FailureVerdict.Replace].
 * - **Android 13 and later ([sdkInt] ≥ 33):** the `android.security.KeyStoreException` in the cause chain
 *   decides. Transient, a system error, or a wait for the user is the Keystore's own trouble:
 *   [FailureVerdict.Keep]. A code that blames the key ([KEY_SPECIFIC_CODES]) replaces. With no such
 *   cause, an `UnrecoverableKeyException` is a verdict on the stored key (a permanently invalidated key at
 *   load, an EC key with no certificate) and replaces. Everything else is [FailureVerdict.Unknown].
 * - **Below 33:** no public API says whose failure it is, so everything else is [FailureVerdict.Unknown].
 *
 * `isTransientFailure()` alone is not the keep test: it is `false` for `SYSTEM_ERROR`, the code a
 * Keystore that is merely failing right now answers with.
 *
 * The bound this leaves: [FailureVerdict.Keep] never replaces, so a key that fails forever with a
 * system-error code throws on every launch until the app's data is cleared; and an outage over an hour
 * long whose codes carry no flag (`BACKEND_BUSY`, a dead keystore2 binder) replaces the key.
 */
@SuppressLint("NewApi") // Every KeyStoreException call is behind sdkInt >= KEYSTORE_EXCEPTION_API, which lint cannot see.
internal fun judge(failure: KeystoreFailure, sdkInt: Int): FailureVerdict {
    if (failure is KeystoreFailure.Thrown && failure.error is KeyPermanentlyInvalidatedException) {
        return FailureVerdict.Replace("the Keystore reports it permanently invalidated")
    }
    if (sdkInt < KEYSTORE_EXCEPTION_API) return FailureVerdict.Unknown
    val error = (failure as? KeystoreFailure.Thrown)?.error ?: return FailureVerdict.Unknown
    val keystore = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<KeyStoreException>().firstOrNull()
    return when {
        keystore == null && error is UnrecoverableKeyException ->
            FailureVerdict.Replace("the Keystore cannot recover it (UnrecoverableKeyException)")
        keystore == null -> FailureVerdict.Unknown
        keystore.isTransientFailure || keystore.isSystemError || keystore.requiresUserAuthentication() -> FailureVerdict.Keep
        keystore.numericErrorCode in KEY_SPECIFIC_CODES ->
            FailureVerdict.Replace("the Keystore reports it damaged (KeyStoreException code ${keystore.numericErrorCode})")
        else -> FailureVerdict.Unknown
    }
}

/** How far down a cause chain [judge] looks; a chain is two or three links in practice. */
private const val MAX_CAUSE_DEPTH = 8
