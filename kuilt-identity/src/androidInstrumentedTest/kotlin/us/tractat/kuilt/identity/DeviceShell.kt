package us.tractat.kuilt.identity

import android.os.ParcelFileDescriptor
import android.security.KeyStoreException
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Shell commands on the device under test, as the `shell` user, and as root where the build allows it.
 *
 * Root is what lets a device run reach a real Keystore fault: corrupting a stored key, stopping the
 * Keystore daemon. An emulator's `userdebug` image (`google_apis`, not `google_apis_playstore`) has it;
 * a retail phone does not, and every probe that needs it is skipped there by assumption, so the result
 * file names it as skipped rather than as passed.
 *
 * `UiAutomation.executeShellCommand` splits its argument on spaces and runs no shell, so a command with
 * quotes or pipes is written to a script in the app's cache directory and run by `sh`.
 */
internal object DeviceShell {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** [command] as the `shell` user, split on spaces; its standard output. */
    fun run(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    /** Whether `su` gives this device run root. */
    val hasRoot: Boolean by lazy { run("su 0 id").contains("uid=0") }

    /** [script] run by `sh` as root; its standard output and standard error. */
    fun asRoot(script: String): String {
        check(hasRoot) { "precondition: root on this device" }
        val file = File(instrumentation.context.cacheDir, "w3226-${System.nanoTime()}.sh")
        file.writeText("$script\n")
        file.setReadable(true, false)
        return try {
            run("su 0 sh ${file.absolutePath}")
        } finally {
            file.delete()
        }
    }

    /** This app's uid, the namespace the Keystore files its keys under. */
    val uid: Int get() = android.os.Process.myUid()
}

/**
 * Everything a [Throwable] chain says that [judge] reads, one link per line: the class, the message, and
 * for an `android.security.KeyStoreException` its public code and flags. What a device run records.
 */
internal fun Throwable?.describeChain(): String {
    if (this == null) return "(nothing thrown)"
    return generateSequence(this) { it.cause }.take(8).joinToString(" <- ") { link ->
        val keystore = (link as? KeyStoreException)?.let {
            if (android.os.Build.VERSION.SDK_INT >= KEYSTORE_EXCEPTION_API) {
                " [code=${it.numericErrorCode} transient=${it.isTransientFailure} system=${it.isSystemError} " +
                    "userAuth=${it.requiresUserAuthentication()}]"
            } else {
                ""
            }
        }.orEmpty()
        "${link::class.java.name}(${link.message})$keystore"
    }
}
