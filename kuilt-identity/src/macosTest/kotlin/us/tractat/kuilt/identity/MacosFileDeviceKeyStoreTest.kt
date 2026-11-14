@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.stat
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the macOS file store promises beyond the shared contract ([MacosFileDeviceKeyStoreConformanceTest]):
 * how well it keeps the key file, what it keeps of a refused one, what a key file it cannot read does,
 * and the one-line default's directory. On the real file system.
 */
class MacosFileDeviceKeyStoreTest {

    /** The key file is created `rw-------`: only this user can read the key. */
    @Test
    fun keyFileIsOwnerOnly() = runTest {
        val directory = freshDirectory("kuilt-device-key")
        fileDeviceKeyStore(directory, {}).deviceKey()
        val mode = modeOf("$directory/${FileKeyMedium.KEY_FILE}")
        assertEquals("600", mode.toString(8), "the key file's permission bits")
    }

    /** A refused key file is renamed aside with its bytes intact, and the new key written in its place. */
    @Test
    fun aRefusedKeyIsKeptAsideNotOverwritten() = runTest {
        val directory = freshDirectory("kuilt-device-key")
        val first = fileDeviceKeyStore(directory, {}).deviceKey().id
        val keyFile = "$directory/${FileKeyMedium.KEY_FILE}"
        val damaged = byteArrayOf(1, 2, 3)
        writeFile(keyFile, damaged)

        val replacement = fileDeviceKeyStore(directory, {}).deviceKey().id
        val aside = readFile("$keyFile.refused-1")

        assertAll(
            { assertNotEquals(first, replacement, "the damaged key is replaced") },
            { assertContentEquals(damaged, aside, "and kept, byte for byte, as device-key.jwk.refused-1") },
        )
    }

    /**
     * A key file this user may not read throws, rather than starting a new device, and makes no key;
     * once it can be read again, the key is the same key. The permission arm of the contract's
     * unreadable-key property, whose own rig is a symbolic-link loop.
     */
    @Test
    fun aKeyFileWithoutReadPermissionThrowsAndKeepsTheKey() = runTest {
        val directory = freshDirectory("kuilt-device-key")
        val held = fileDeviceKeyStore(directory, {}).deviceKey().id
        val keyFile = "$directory/${FileKeyMedium.KEY_FILE}"
        check(chmod(keyFile, 0u) == 0) { "precondition: the key file's permissions were removed" }

        var made = 0
        val failure = failureOf { fileDeviceKeyStore(directory) { made++ }.deviceKey() }
        check(chmod(keyFile, (S_IRUSR or S_IWUSR).toUShort()) == 0)
        val after = fileDeviceKeyStore(directory, {}).deviceKey().id

        assertAll(
            { assertTrue(failure is IllegalStateException, "an unreadable key file throws IllegalStateException: $failure") },
            { assertTrue("errno=" in failure?.message.orEmpty(), "naming the errno: ${failure?.message}") },
            { assertEquals(0, made, "and makes no key") },
            { assertEquals(held, after, "once it can be read, the key is the same key") },
        )
    }

    /**
     * The one-line default keeps the key under `<Application Support>/<appId>/kuilt-identity`, remembers
     * it, and keeps each app id apart.
     */
    @Test
    fun platformKeepsEachAppApartAndRemembersIt() = runTest {
        val support = freshDirectory("kuilt-application-support")
        val me = macosDeviceKeyStore("my-app", support, {}).deviceKey().id
        val again = macosDeviceKeyStore("my-app", support, {}).deviceKey().id
        val otherApp = macosDeviceKeyStore("other-app", support, {}).deviceKey().id
        val keyFile = "$support/my-app/kuilt-identity/${FileKeyMedium.KEY_FILE}"
        assertAll(
            { assertTrue(NSFileManager.defaultManager.fileExistsAtPath(keyFile), "the key file is $keyFile") },
            { assertEquals(me, again, "the same app id is the same device") },
            { assertNotEquals(me, otherApp, "another app id is another device") },
        )
    }

    @Test
    fun platformRefusesAnAppIdThatIsNotOneSegment() {
        val support = freshDirectory("kuilt-application-support")
        val bad = listOf("", ".", "..", "a/b", "a b", "../x")
        val refused = bad.filter { appId ->
            try {
                macosDeviceKeyStore(appId, support, {})
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
        assertEquals(bad, refused)
    }

    /** The default's parent is this user's Application Support folder, which does not roam. */
    @Test
    fun platformDirectoryIsApplicationSupport() {
        val support = applicationSupportDirectory()
        assertAll(
            { assertTrue(support.endsWith("/Library/Application Support"), "Application Support: $support") },
            { assertEquals("$support/my-app/kuilt-identity", identityDirectory(support, "my-app")) },
        )
    }

    private fun modeOf(path: String): Int = memScoped {
        val info = alloc<stat>()
        check(stat(path, info.ptr) == 0) { "precondition: $path exists" }
        info.st_mode.toInt() and 0x1FF
    }

    /** What [block] threw, or `null` if it returned. This test's own cancellation is never a verdict. */
    private suspend fun failureOf(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
}
