package us.tractat.kuilt.identity

import android.os.Build
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.KeystoreFaults.Outcome
import us.tractat.kuilt.test.assertAll
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What the device's real Keystore throws for each fault the store has to tell apart (#3226), and what
 * the store then does. `FakeKeystore` is built from AOSP's source; these rows are the measurement it
 * was built without. Each row logs the full cause chain under [TAG], so a run on new hardware says what
 * that hardware answered: `adb logcat -s kuilt-identity-device`.
 */
class KeystoreFaultDeviceTest {
    private val alias = "kuilt-identity-device-test-${UUID.randomUUID()}"
    private val directory: File = Files.createTempDirectory("kuilt-keystore-device").toFile()
    private var clock = START

    private fun store(made: () -> Unit = {}): DeviceKeyStore =
        AndroidKeystoreDeviceKeyStore(alias, directory, AndroidKeystoreBackend, Build.VERSION.SDK_INT, { clock }) { made() }

    @AfterTest
    fun cleanUp() {
        if (DeviceShell.hasRoot) KeystoreFaults.startKeystore()
        AndroidKeystoreBackend.delete(alias)
        directory.deleteRecursively()
    }

    /**
     * A stored key blob overwritten with random bytes, as flash corruption or a botched restore leaves it.
     * Whatever the Keystore answers, the store must not judge it [FailureVerdict.Keep] — that would throw
     * on every launch and never replace the key — and within the hour of failures Decided 29 allows, the
     * device carries on under a new key.
     */
    @Test
    fun aCorruptKeyBlobIsReplacedWithinTheHour() = runTest {
        val original = store().deviceKey().id
        val rig = KeystoreFaults.corruptKeyBlob(alias)
        val failure = backendFailure()
        val verdict = judge(failure, Build.VERSION.SDK_INT)
        val atOnce = outcomeOf { store().deviceKey().id }
        clock += 61.minutes.inWholeMilliseconds
        val anHourOn = outcomeOf { store().deviceKey().id }
        record("corrupt key blob", "rig=${rig.trim()} failure=${failure.description} cause=${failure.cause.describeChain()} verdict=${verdict.name} atOnce=$atOnce anHourOn=$anHourOn")
        val replacement = (anHourOn as? Outcome.Returned)?.value ?: (atOnce as? Outcome.Returned)?.value
        assertAll(
            { assertTrue(verdict !is FailureVerdict.Keep, "a corrupt blob is never the Keystore's own trouble: ${failure.cause.describeChain()}") },
            { assertNotNull(replacement, "within the hour the device has a key again: at once $atOnce, an hour on $anHourOn") },
            { assertNotEquals(original, replacement, "and it is a new key") },
        )
    }

    /**
     * The Keystore daemon stopped. On Android 12 and later `getKey` throws; on Android 11 and earlier it
     * answers "no key" ([KeystoreBackend]'s claim, read from AOSP), and only the store's marker tells that
     * from a first launch. Either way the store throws, makes no key, and has the same key once the
     * daemon is back.
     */
    @Test
    fun aStoppedKeystoreIsRefusedNotReplaced() = runTest {
        val original = store().deviceKey().id
        KeystoreFaults.stopKeystore()
        val getKey = KeystoreFaults.bounded { AndroidKeystoreBackend.getKey(alias) }
        val getCertificate = KeystoreFaults.bounded { AndroidKeystoreBackend.getCertificatePublicKey(alias) }
        var made = 0
        val during = outcomeOf { store { made++ }.deviceKey().id }
        KeystoreFaults.startKeystore()
        val after = store().deviceKey().id
        record("stopped keystore", "getKey=${getKey.describe()} getCertificate=${getCertificate.describe()} store=$during")
        assertAll(
            {
                if (Build.VERSION.SDK_INT <= LAST_API_WHERE_GET_KEY_HIDES_FAILURE) {
                    assertEquals(Outcome.Returned(null), getKey, "on Android 11 and earlier getKey answers no key")
                } else {
                    assertIs<Outcome.Threw>(getKey, "on Android 12 and later getKey throws")
                }
            },
            { assertIs<Outcome.Threw>(during, "the store throws while the Keystore is down") },
            { assertEquals(0, made, "and makes no key") },
            { assertEquals(original, after, "and has the same key once it is back") },
        )
    }

    /**
     * A key that needs the screen lock, after the screen lock is removed: the Keystore invalidates it for
     * good, and the store replaces it at once on every version. The rig needs no root.
     */
    @Test
    fun aKeyInvalidatedForGoodIsReplacedAtOnce() = runTest {
        KeystoreFaults.plantKeyInvalidatedByRemovingTheScreenLock(alias)
        val failure = backendFailure()
        val verdict = judge(failure, Build.VERSION.SDK_INT)
        var made = 0
        val atOnce = outcomeOf { store { made++ }.deviceKey().id }
        record("invalidated key", "failure=${failure.description} cause=${failure.cause.describeChain()} verdict=${verdict.name} store=$atOnce")
        assertAll(
            { assertIs<FailureVerdict.Replace>(verdict, "the Keystore's verdict that the key is gone for good: ${failure.cause.describeChain()}") },
            { assertIs<Outcome.Returned<DidKey>>(atOnce, "the store carries on at once") },
            { assertEquals(1, made, "under one new key") },
        )
    }

    /** The first thing the store would see fail, reading the key under [alias] as it does. */
    private suspend fun backendFailure(): KeystoreFailure {
        val signer = try {
            AndroidKeystoreBackend.getKey(alias)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return KeystoreFailure.Thrown("loading the key", e)
        } ?: return KeystoreFailure.NoKeyThoughOneWasMade
        val publicKey = AndroidKeystoreBackend.getCertificatePublicKey(alias) ?: return KeystoreFailure.NoCertificate
        return when (val read = readBack(publicKey, signer, SelfTestVerifier.Provider)) {
            is KeystoreRead.Failed -> read.failure
            else -> error("rig: the key read back as $read, so nothing failed")
        }
    }

    private suspend fun <T> outcomeOf(block: suspend () -> T): Outcome<T> = try {
        Outcome.Returned(block())
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Outcome.Threw(e)
    }

    private fun Outcome<*>.describe(): String = when (this) {
        is Outcome.Threw -> "threw ${error.describeChain()}"
        else -> toString()
    }

    private val FailureVerdict.name: String get() = when (this) {
        is FailureVerdict.Replace -> "Replace($why)"
        FailureVerdict.Keep -> "Keep"
        FailureVerdict.Unknown -> "Unknown"
    }

    private fun record(what: String, measured: String) {
        Log.i(TAG, "API ${Build.VERSION.SDK_INT} ${Build.FINGERPRINT}: $what: $measured")
    }

    private companion object {
        const val TAG = "kuilt-identity-device"
        const val START = 1_700_000_000_000L
    }
}
