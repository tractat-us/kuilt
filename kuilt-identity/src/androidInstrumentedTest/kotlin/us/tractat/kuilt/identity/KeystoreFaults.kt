package us.tractat.kuilt.identity

import android.app.KeyguardManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicReference

/**
 * Real faults in the device's Keystore, rigged through [DeviceShell]: the two a device run exists to
 * measure, a stored key whose bytes are not a key and a Keystore that does not answer.
 *
 * Android 12 (API 31) and later keep keys in keystore2's SQLite database, `persistent.sqlite`, one
 * `keyentry` row per alias and the key's encrypted blob in `blobentry` (subcomponent 0); the daemon is
 * the `keystore2` service. Android 11 and earlier keep one file per key under `user_0`, named
 * `<uid>_USRPKEY_<alias>`, and the daemon is `keystore`. Every rig needs root, and assumes it.
 */
internal object KeystoreFaults {
    private val keystore2 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val daemon get() = if (keystore2) "keystore2" else "keystore"

    fun assumeRoot() {
        assumeTrue("rig: needs root, which this device's build does not give (a retail or Play Store image)", DeviceShell.hasRoot)
    }

    /** Overwrites the stored key blob under [alias] with random bytes of the same length. Returns what the shell said. */
    fun corruptKeyBlob(alias: String): String {
        assumeRoot()
        val out = if (keystore2) {
            DeviceShell.asRoot(
                """
                DB=/data/misc/keystore/persistent.sqlite
                ID=$(sqlite3 ${'$'}DB "SELECT id FROM keyentry WHERE alias='$alias' AND namespace=${DeviceShell.uid} AND domain=0;")
                echo "keyentry=${'$'}ID"
                sqlite3 ${'$'}DB "UPDATE blobentry SET blob=randomblob(length(blob)) WHERE keyentryid=${'$'}ID AND subcomponent_type=0; SELECT changes();"
                """.trimIndent(),
            )
        } else {
            DeviceShell.asRoot(
                """
                F=$(ls /data/misc/keystore/user_0/ | grep '^${DeviceShell.uid}_USRPKEY_' | grep '${alias.takeLast(SUFFIX)}')
                echo "file=${'$'}F"
                N=$(stat -c %s /data/misc/keystore/user_0/${'$'}F)
                dd if=/dev/urandom of=/data/misc/keystore/user_0/${'$'}F bs=1 seek=64 count=$((N-64)) conv=notrunc 2>&1
                """.trimIndent(),
            )
        }
        check(out.contains(if (keystore2) "\n1" else "file=${DeviceShell.uid}_USRPKEY_")) { "rig: the key blob under $alias was not found to corrupt: $out" }
        return out
    }

    /**
     * Deletes keystore2's metadata rows for the key under [alias] (Android 12 and later), which keystore2
     * answers with `VALUE_CORRUPTED`, public code 7: measured on Android 14, `getKey` throws
     * `UnrecoverableKeyException` caused by `KeyStoreException` code 7 ("Trying to get creation date").
     * The one real corruption found that the Keystore itself calls corrupted; a key blob of random bytes
     * is code 10 instead, which Decided 29 deliberately waits an hour on.
     */
    fun corruptKeyMetadata(alias: String): String {
        assumeRoot()
        check(keystore2) { "precondition: keystore2, Android 12 and later" }
        val out = DeviceShell.asRoot(
            """
            DB=/data/misc/keystore/persistent.sqlite
            ID=$(sqlite3 ${'$'}DB "SELECT id FROM keyentry WHERE alias='$alias' AND namespace=${DeviceShell.uid} AND domain=0;")
            sqlite3 ${'$'}DB "DELETE FROM keymetadata WHERE keyentryid=${'$'}ID; SELECT changes();"
            """.trimIndent(),
        )
        check((out.trim().lines().lastOrNull()?.toIntOrNull() ?: 0) > 0) { "rig: no metadata under $alias to delete: $out" }
        return out
    }

    /** Whether the Keystore still holds a key blob under [alias], read off its storage as root. */
    fun keyBlobExists(alias: String): Boolean {
        val out = if (keystore2) {
            DeviceShell.asRoot(
                """
                sqlite3 /data/misc/keystore/persistent.sqlite "SELECT count(*) FROM blobentry b JOIN keyentry k ON b.keyentryid=k.id WHERE k.alias='$alias' AND k.namespace=${DeviceShell.uid} AND b.subcomponent_type=0;"
                """.trimIndent(),
            )
        } else {
            DeviceShell.asRoot("ls /data/misc/keystore/user_0/ | grep '^${DeviceShell.uid}_USRPKEY_' | grep -c '${alias.takeLast(SUFFIX)}'")
        }
        return (out.trim().lines().lastOrNull()?.toIntOrNull() ?: 0) > 0
    }

    /**
     * Deletes [alias] in a test's cleanup, logging rather than throwing when the Keystore cannot: after a
     * stopped daemon on Android 11 this process reaches no Keystore at all, and a throwing cleanup would
     * fail every row after it for the rig's sake rather than its own.
     */
    fun deleteTolerantly(alias: String) {
        try {
            AndroidKeystoreBackend.delete(alias)
        } catch (e: KeyStoreException) {
            Log.w("kuilt-identity-device", "cleanup: could not delete $alias: ${e.describeChain()}")
        }
    }

    /** Stops the Keystore daemon, and waits until init reports it stopped. */
    fun stopKeystore() {
        assumeRoot()
        DeviceShell.asRoot("setprop ctl.stop $daemon")
        awaitState("stopped")
    }

    /**
     * Starts the Keystore daemon again, and waits until init reports it running **and** its binder service
     * is registered. Running alone is not enough: measured on Android 14, a call made between the two
     * still fails as if the daemon were down.
     */
    fun startKeystore() {
        DeviceShell.asRoot("setprop ctl.start $daemon")
        awaitState("running")
        awaitUntil("$service registered") { DeviceShell.run("service check $service").contains(": found") }
    }

    private val service get() = if (keystore2) "android.system.keystore2.IKeystoreService/default" else "android.security.keystore"

    private fun awaitState(state: String) {
        awaitUntil("$daemon '$state' (it is '${DeviceShell.run("getprop init.svc.$daemon").trim()}')") {
            DeviceShell.run("getprop init.svc.$daemon").trim() == state
        }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        repeat(STATE_POLLS) {
            if (condition()) return
            Thread.sleep(STATE_POLL_MS)
        }
        error("rig: never reached $what")
    }

    /** Runs [block] on a thread of its own, bounded, so a Keystore call that blocks on a dead daemon cannot wedge the run. */
    fun <T> bounded(block: () -> T): Outcome<T> {
        val result = AtomicReference<Outcome<T>>(Outcome.StillBlocked)
        val thread = Thread {
            result.set(
                try {
                    Outcome.Returned(block())
                } catch (e: Throwable) {
                    Outcome.Threw(e)
                },
            )
        }
        thread.start()
        thread.join(CALL_BOUND_MS)
        return result.get()
    }

    /** What a [bounded] Keystore call did. */
    sealed interface Outcome<out T> {
        data class Returned<T>(val value: T) : Outcome<T>

        data class Threw(val error: Throwable) : Outcome<Nothing>

        data object StillBlocked : Outcome<Nothing>
    }

    /**
     * Makes a key under [alias] that needs the screen lock, then removes the screen lock, which the
     * Keystore answers by being done with that key for good: the one damage a device run can rig without
     * root. Needs a device with no screen lock of its own, since setting one is how the key is made and
     * removing it needs the old PIN; a phone with a lock skips this by assumption.
     *
     * Android 13 and later only. Measured on an Android 11 emulator, removing the screen lock left the
     * whole Keystore "not initialized" (`KeyStoreException: Keystore not initialized`) for every key of
     * the app until a reboot, so the rig there damaged every row after it rather than one key.
     */
    fun plantKeyInvalidatedByRemovingTheScreenLock(alias: String) {
        assumeTrue("rig: below Android 13 removing the screen lock breaks the whole Keystore until a reboot", Build.VERSION.SDK_INT >= KEYSTORE_EXCEPTION_API)
        val keyguard = InstrumentationRegistry.getInstrumentation().context.getSystemService(KeyguardManager::class.java)
        assumeTrue("rig: this device has a screen lock of its own, and the rig would have to remove it", !keyguard.isDeviceSecure)
        val set = DeviceShell.run("locksettings set-pin $RIG_PIN")
        check(keyguard.isDeviceSecure) { "rig: setting a PIN did not take: $set" }
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(authBoundSpec(alias))
                generateKeyPair()
            }
        } finally {
            val cleared = DeviceShell.run("locksettings clear --old $RIG_PIN")
            check(!keyguard.isDeviceSecure) { "rig: the PIN it set could not be removed: $cleared" }
        }
    }

    @Suppress("DEPRECATION") // setUserAuthenticationValidityDurationSeconds is the only form below API 30.
    private fun authBoundSpec(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                }
            }
            .build()

    private const val RIG_PIN = "1234"
    private const val AUTH_SECONDS = 30
    private const val SUFFIX = 12
    private const val STATE_POLLS = 100
    private const val STATE_POLL_MS = 100L
    private const val CALL_BOUND_MS = 30_000L
}
