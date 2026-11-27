package us.tractat.kuilt.identity

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * `AndroidJUnitRunner`, with kotlin-logging sent to logcat before any test class loads.
 *
 * On Android, kotlin-logging logs through SLF4J unless the system property
 * `kotlin-logging-to-android-native` is `true`, and it declares no SLF4J dependency. So in a process
 * with no SLF4J on its classpath, the first `KotlinLogging.logger` throws `NoClassDefFoundError`
 * for `org.slf4j.LoggerFactory` — measured on this device run, at the store's file-level logger, before
 * it reached the Keystore at all (#3744). An app has to choose one of the two; this run chooses logcat,
 * where the store's warnings can be read off the device with `adb logcat`.
 */
class IdentityDeviceTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        System.setProperty("kotlin-logging-to-android-native", "true")
        super.onCreate(arguments)
    }
}
