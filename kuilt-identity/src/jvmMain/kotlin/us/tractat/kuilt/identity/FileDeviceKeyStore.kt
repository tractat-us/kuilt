package us.tractat.kuilt.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.store.StoreKey
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermissions

private val logger = KotlinLogging.logger("us.tractat.kuilt.identity.FileDeviceKeyStore")

/**
 * A [DeviceKeyStore] for the desktop JVM: the device key in a file only this user can read, and the
 * grants beside it, in one directory that outlives the process.
 *
 * Open it over the same directory after a restart and it is the same device. The key lives in
 * `device-key.jwk`, a JWK carrying `x`, `y` and `d`, created with POSIX mode `0600`. It is read back
 * with a self-test — sign, then verify under the name the file's public half gives — so a file whose
 * two halves do not belong together is refused, not trusted. The grants live in a
 * [FileChannelDurableStore] over the same directory, since a grant is not a secret.
 *
 * ## When the file is damaged
 *
 * A key file that is not a P-256 JWK, or fails its self-test, is **replaced** with a new key: the
 * device carries on as a new device, with one warning in the log and no throw, which is what someone
 * whose file was truncated or edited expects. A grants record that cannot be read is dropped the same
 * way; a lost grant costs only an ordinary admission. Removing the key file is the deliberate way to
 * start again as a new device.
 *
 * A file that exists but cannot be **read** at all — no permission, an I/O error — is not damage, and
 * throws [java.io.IOException]: replacing a key the store could not even look at would silently turn
 * a passing fault into a new identity.
 *
 * ## Crash safety
 *
 * A key is written to `device-key.jwk.tmp`, forced to the device, renamed over `device-key.jwk`, and
 * then the directory is forced, so the key file always holds a whole key: the old one until the
 * rename, the new one after. A crash leaves at most the temporary file, which nothing reads and the
 * next write replaces. On Windows the directory cannot be forced and the rename rests on NTFS's
 * journal, as [FileChannelDurableStore] describes; and there the key file has no POSIX mode, so it
 * takes the directory's access-control list. Give it a directory only this user can read.
 *
 * ## What it does not do
 *
 * One directory belongs to one process. Two processes opening the same directory at once can each
 * make a first key, and the one renamed last is kept. It keeps no backup of a replaced key, and it
 * does not drop grants naming a key it replaced; those name a key the device no longer holds, so a
 * room will not accept them.
 *
 * Safe under a multi-threaded dispatcher: one [Mutex] guards the key, the grants and every file write.
 * File I/O runs on the caller's dispatcher, as [FileChannelDurableStore]'s does; it is two small files.
 *
 * @param directory the directory that holds this device's files. Created, with any missing ancestors,
 *   if it does not exist.
 */
public class FileDeviceKeyStore internal constructor(
    directory: Path,
    /** Called each time this store is about to make a key, and at no other time: the TCK's gate. */
    private val beforeKeyIsMade: suspend () -> Unit,
) : DeviceKeyStore {
    /** A store over [directory]. */
    public constructor(directory: Path) : this(directory, {})

    private val directory: Path = directory.toAbsolutePath()

    // Constructed first: it creates the directory, forcing each new ancestor's entry.
    private val grantStore = FileChannelDurableStore(this.directory.toFile())
    private val keyFile: Path = this.directory.resolve(KEY_FILE)
    private val keyTemp: Path = this.directory.resolve("$KEY_FILE.tmp")

    private val mutex = Mutex()
    private var key: DeviceKey? = null
    private var held: List<Grant>? = null

    override suspend fun deviceKey(): DeviceKey = mutex.withLock {
        key ?: readOrMake().also { key = it }
    }

    override suspend fun grants(): List<Grant> = mutex.withLock { loadedGrants() }

    override suspend fun keepGrant(grant: Grant): Unit = mutex.withLock {
        val current = loadedGrants()
        val next = current.keeping(grant)
        if (next !== current) {
            grantStore.write(GRANTS, StoredGrants.encode(next))
            held = next
        }
    }

    private suspend fun readOrMake(): DeviceKey {
        if (!Files.exists(keyFile)) return make()
        val stored = Files.readAllBytes(keyFile)
        val read = try {
            SoftwareDeviceKey.readBack(stored)
        } finally {
            stored.fill(0)
        }
        return when (read) {
            is StoredKey.Usable -> read.key
            is StoredKey.Refused -> {
                logger.warn {
                    "kuilt-identity: the device key in $keyFile cannot be used: ${read.reason}. It is replaced " +
                        "with a new key, so this device is a new device to every room it returns to."
                }
                make()
            }
        }
    }

    private suspend fun make(): DeviceKey {
        beforeKeyIsMade()
        val made = SoftwareDeviceKey.generate()
        val jwk = made.encodeJwk()
        try {
            writeKeyFile(jwk)
        } finally {
            jwk.fill(0)
        }
        return made
    }

    /** Write to a temporary file created owner-only, force it, rename it over the key, force the directory. */
    private fun writeKeyFile(jwk: ByteArray) {
        Files.deleteIfExists(keyTemp)
        FileChannel.open(keyTemp, setOf(CREATE_NEW, WRITE), *ownerOnly()).use { channel ->
            val buffer = ByteBuffer.wrap(jwk)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(keyTemp, keyFile, ATOMIC_MOVE, REPLACE_EXISTING)
        if (!ON_WINDOWS) FileChannel.open(directory, READ).use { it.force(true) }
    }

    private fun ownerOnly(): Array<FileAttribute<*>> =
        if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
            arrayOf(PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE)))
        } else {
            emptyArray()
        }

    private suspend fun loadedGrants(): List<Grant> = held ?: readGrants().also { held = it }

    private suspend fun readGrants(): List<Grant> {
        val record = grantStore.read(GRANTS) ?: return emptyList()
        return StoredGrants.decode(record) ?: emptyList<Grant>().also {
            logger.warn {
                "kuilt-identity: the grants kept in $directory cannot be read and are dropped; this device " +
                    "returns to each room as an ordinary admission until it is granted again."
            }
        }
    }

    internal companion object {
        /** The key file's name in the store's directory. */
        const val KEY_FILE: String = "device-key.jwk"

        /** The [StoreKey] the grants record is kept under. */
        val GRANTS: StoreKey = StoreKey("grants")

        private val ON_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

/**
 * This device's key store on the desktop JVM: a [FileDeviceKeyStore] over `directory/appId`, so apps
 * sharing one parent directory are different devices to each other.
 *
 * Pass a directory that outlives the app and that only this user can read, such as one under the
 * user's home or application-data directory; kuilt does not guess one. Opening it again with the same
 * [appId] and [directory] after a restart is the same device.
 *
 * @param appId one path segment naming the app: letters, digits, `.`, `_` and `-`, and neither `.` nor
 *   `..`. On a case-insensitive filesystem two ids differing only in case name one device.
 * @throws IllegalArgumentException when [appId] is not one such segment.
 * @sample us.tractat.kuilt.identity.sampleDeviceKeyStoreRemembers
 */
public fun DeviceKeyStore.Companion.platform(appId: String, directory: Path): DeviceKeyStore {
    require(APP_ID.matches(appId) && appId != "." && appId != "..") {
        "appId must be one path segment of letters, digits, '.', '_' and '-', and not '.' or '..': \"$appId\""
    }
    return FileDeviceKeyStore(directory.resolve(appId))
}

private val APP_ID = Regex("[A-Za-z0-9._-]+")
