package us.tractat.kuilt.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import us.tractat.kuilt.test.assertAll
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the desktop file store promises beyond the shared contract: how well it keeps the key, what an
 * operator reads when a key is replaced, and the one-line platform default. The contract itself is
 * [FileDeviceKeyStoreConformanceTest].
 */
class FileDeviceKeyStoreTest {

    /**
     * The key file is `rw-------`, the first time and after a replacement. The temp file it is renamed
     * from is created that way, so no other user could read the key even in the instant before rename.
     */
    @Test
    fun keyFileIsOwnerOnly() = runTest {
        val directory = createTempDirectory("kuilt-device-key")
        val keyFile = directory.resolve(FileDeviceKeyStore.KEY_FILE)
        val first = FileDeviceKeyStore(directory).deviceKey().id
        val made = permissionsOf(keyFile)
        keyFile.writeBytes(byteArrayOf(1, 2, 3))
        val replacement = FileDeviceKeyStore(directory).deviceKey().id
        val replaced = permissionsOf(keyFile)

        assertAll(
            { assertNotEquals(first, replacement, "precondition: the key really was replaced") },
            { assertEquals("rw-------", made, "a made key is owner-only") },
            { assertEquals("rw-------", replaced, "and so is its replacement") },
            { assertFalse(directory.resolve("${FileDeviceKeyStore.KEY_FILE}.tmp").exists(), "no temp file is left behind") },
        )
    }

    /**
     * A replaced key leaves exactly one warning, naming the file and the reason — and none of the key
     * material: not the old file's private part, not the new one's. Read off the real Logback event an
     * operator would see, at the level that decides whether it is recorded at all.
     */
    @Test
    fun aReplacedKeyIsWarnedAboutOnceWithoutKeyMaterial() = runTest {
        val directory = createTempDirectory("kuilt-device-key")
        val keyFile = directory.resolve(FileDeviceKeyStore.KEY_FILE)
        val named = SoftwareDeviceKey.generate()
        val signer = SoftwareDeviceKey.generate()
        val spliced = withPrivatePart(named.encodeJwk().decodeToString(), of = signer.encodeJwk().decodeToString())
        keyFile.writeBytes(spliced.encodeToByteArray())

        val (warnings, replacement) = capturing { FileDeviceKeyStore(directory).deviceKey() }
        val newPrivatePart = privatePartOf(keyFile.readBytes().decodeToString())
        val text = warnings.joinToString("\n") { it.formattedMessage }

        assertAll(
            { assertNotEquals(named.id, replacement.id, "precondition: the mismatched key was refused") },
            { assertEquals(1, warnings.size, "one warning for one replacement: $text") },
            { assertTrue(keyFile.toString() in text, "the warning names the file: $text") },
            { assertTrue("does not sign" in text, "and the reason: $text") },
            { assertFalse(privatePartOf(spliced) in text, "never the refused key's private part") },
            { assertFalse(newPrivatePart in text, "nor the new key's") },
        )
    }

    /**
     * A key file that exists and cannot be read is an error, not a reason to become a new device.
     *
     * The rig is a key file with no permissions at all, which this user cannot read but the store
     * *could* rename a new key over, since a rename needs only the directory's permission. That is what
     * makes it discriminate: a store that treated the read failure as damage would replace the key
     * without a throw, and the file's old bytes would be gone. (A directory in the key file's place
     * would not do: the replacing rename fails on it too, so both behaviours would throw.)
     */
    @Test
    fun anUnreadableKeyFileThrowsRatherThanBecomingANewDevice() = runTest {
        val directory = createTempDirectory("kuilt-device-key")
        val keyFile = directory.resolve(FileDeviceKeyStore.KEY_FILE)
        FileDeviceKeyStore(directory).deviceKey()
        val before = keyFile.readBytes()
        Files.setPosixFilePermissions(keyFile, emptySet())
        val readable = Files.isReadable(keyFile)

        val thrown = runCatchingIo { FileDeviceKeyStore(directory).deviceKey() }
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
        val after = keyFile.readBytes()

        assertAll(
            { assertFalse(readable, "precondition: this user cannot read the key file (not running as root)") },
            { assertTrue(thrown is IOException, "an unreadable key file throws IOException, got $thrown") },
            { assertTrue(before.contentEquals(after), "and the key file was not replaced") },
        )
    }

    /**
     * A key file whose existence cannot be settled — here a symbolic link to itself, which reads fail
     * with `ELOOP` and an existence probe answers "absent" — is an error, not a first launch. The store
     * must not rename a new key over it: that would replace the identity without a warning.
     */
    @Test
    fun aKeyFileThatCannotBeResolvedThrowsRatherThanBecomingANewDevice() = runTest {
        val directory = createTempDirectory("kuilt-device-key")
        val keyFile = directory.resolve(FileDeviceKeyStore.KEY_FILE)
        FileDeviceKeyStore(directory).deviceKey()
        val aside = directory.resolve("held-by-test")
        Files.move(keyFile, aside)
        Files.createSymbolicLink(keyFile, keyFile.fileName)
        val probeSaysAbsent = !Files.exists(keyFile)

        val thrown = runCatchingIo { FileDeviceKeyStore(directory).deviceKey() }

        assertAll(
            { assertTrue(probeSaysAbsent, "precondition: an existence probe calls the looping key file absent") },
            { assertTrue(thrown is IOException, "a key file that cannot be resolved throws IOException, got $thrown") },
            { assertTrue(Files.isSymbolicLink(keyFile), "and nothing was renamed over it") },
        )
    }

    /**
     * A refused key is moved aside to `device-key.jwk.refused-<n>`, never overwritten, so a key that was
     * misjudged can still be recovered by hand. A second refusal takes the next number.
     */
    @Test
    fun aRefusedKeyIsKeptAsideNotOverwritten() = runTest {
        val directory = createTempDirectory("kuilt-device-key")
        val keyFile = directory.resolve(FileDeviceKeyStore.KEY_FILE)
        FileDeviceKeyStore(directory).deviceKey()
        val firstDamage = keyFile.readBytes().let { it.copyOf(it.size / 2) }
        keyFile.writeBytes(firstDamage)
        FileDeviceKeyStore(directory).deviceKey()
        val secondDamage = byteArrayOf(1, 2, 3)
        keyFile.writeBytes(secondDamage)
        FileDeviceKeyStore(directory).deviceKey()

        val first = directory.resolve("${FileDeviceKeyStore.KEY_FILE}.refused-1")
        val second = directory.resolve("${FileDeviceKeyStore.KEY_FILE}.refused-2")
        assertAll(
            { assertTrue(first.exists() && first.readBytes().contentEquals(firstDamage), "the first refused key, kept as it was") },
            { assertTrue(second.exists() && second.readBytes().contentEquals(secondDamage), "the second, beside it") },
            { assertEquals("rw-------", permissionsOf(first), "and still owner-only") },
        )
    }

    private suspend fun runCatchingIo(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: IOException) {
        e
    }

    @Test
    fun platformKeepsEachAppApartAndRemembersIt() = runTest {
        val parent = createTempDirectory("kuilt-device-key")
        val one = DeviceKeyStore.platform("one-app", parent).deviceKey().id
        val oneAgain = DeviceKeyStore.platform("one-app", parent).deviceKey().id
        val other = DeviceKeyStore.platform("other.app_2", parent).deviceKey().id

        assertAll(
            { assertEquals(one, oneAgain, "the same app over the same directory is the same device") },
            { assertNotEquals(one, other, "another app beside it is another device") },
            { assertTrue(parent.resolve("one-app").resolve(FileDeviceKeyStore.KEY_FILE).exists(), "kept under directory/appId") },
        )
    }

    @Test
    fun platformRefusesAnAppIdThatIsNotOnePathSegment() {
        val parent = createTempDirectory("kuilt-device-key")
        val notOneSegment = listOf("", ".", "..", "a/b", "../escape", "a\\b", "a b")
        assertAll(
            *notOneSegment.map { appId ->
                { assertFailsWith<IllegalArgumentException>("\"$appId\"") { DeviceKeyStore.platform(appId, parent) }; Unit }
            }.toTypedArray(),
        )
        assertEquals(listOf<Path>(), Files.list(parent).use { it.toList() }, "and nothing was created for any of them")
    }

    /** Runs the documentation sample over the real desktop store, the store its comments describe. */
    @Test
    fun deviceKeyStoreRemembersSampleRuns() = runTest {
        val dir = createTempDirectory("kuilt-device-key")
        sampleDeviceKeyStoreRemembers { DeviceKeyStore.platform("my-app", dir) }
    }

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

    private fun privatePartOf(jwk: String): String =
        checkNotNull(Regex("\"d\"\\s*:\\s*\"([^\"]+)\"").find(jwk)) { "no d in $jwk" }.groupValues[1]

    private suspend fun <T> capturing(block: suspend () -> T): Pair<List<ILoggingEvent>, T> {
        val logger = LoggerFactory.getLogger("us.tractat.kuilt.identity.FileDeviceKeyStore") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            val result = block()
            appender.list.filter { it.level == Level.WARN } to result
        } finally {
            logger.detachAppender(appender)
        }
    }
}
