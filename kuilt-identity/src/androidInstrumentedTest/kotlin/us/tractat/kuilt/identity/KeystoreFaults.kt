package us.tractat.kuilt.identity

import android.app.KeyguardManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.security.KeyPairGenerator
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

    /** Stops the Keystore daemon, and waits until init reports it stopped. */
    fun stopKeystore() {
        assumeRoot()
        DeviceShell.asRoot("setprop ctl.stop $daemon")
        awaitState("stopped")
    }

    /** Starts the Keystore daemon again, and waits until init reports it running. */
    fun startKeystore() {
        DeviceShell.asRoot("setprop ctl.start $daemon")
        awaitState("running")
    }

    private fun awaitState(state: String) {
        repeat(STATE_POLLS) {
            if (DeviceShell.run("getprop init.svc.$daemon").trim() == state) return
            Thread.sleep(STATE_POLL_MS)
        }
        error("rig: $daemon never reached '$state'; it is '${DeviceShell.run("getprop init.svc.$daemon").trim()}'")
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
     * Keystore answers by invalidating that key for good: the one damage a device run can rig without
     * root. Needs a device with no screen lock of its own, since setting one is how the key is made and
     * removing it needs the old PIN; a phone with a lock skips this by assumption.
     */
    fun plantKeyInvalidatedByRemovingTheScreenLock(alias: String) {
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
