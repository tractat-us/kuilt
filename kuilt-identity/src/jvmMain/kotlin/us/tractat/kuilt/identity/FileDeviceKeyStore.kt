package us.tractat.kuilt.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.store.StoreKey
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("us.tractat.kuilt.identity.FileDeviceKeyStore")

/**
 * A [DeviceKeyStore] for the desktop JVM: the device key in a file only this user can read, and the
 * grants beside it, in one directory that outlives the process.
 *
 * Open it over the same directory after a restart and it is the same device. The key lives in
 * `device-key.jwk`, a JWK carrying `x`, `y` and `d`, created with POSIX mode `0600`. It is read back
 * with a self-test — sign, then verify under the name the file's own `x` and `y` give — so a file
 * whose two halves do not belong together is refused, not trusted. The grants live in a
 * [FileChannelDurableStore] over the same directory, since a grant is not a secret.
 *
 * ## Damage is replaced; a file that cannot be read is not
 *
 * A key file that was read and is not a P-256 JWK, or fails its self-test, is **replaced** with a new
 * key: the device carries on as a new device, with one warning in the log and no throw, which is what
 * someone whose file was truncated or edited expects. The refused file is not overwritten: it is
 * moved aside to `device-key.jwk.refused-<n>`, so a key judged wrongly can still be put back by hand.
 * A grants record that cannot be decoded is dropped; a lost grant costs only an ordinary admission.
 * Deleting the key file is the deliberate way to start again as a new device.
 *
 * Anything else that stops the key file being read — no permission, a symbolic-link loop, an I/O or
 * network file-system error — throws [java.io.IOException], and nothing is written. Only an absent
 * file means "no key yet". Replacing a key the store could not even look at would turn a passing
 * fault into a new identity, silently.
 *
 * ## Crash safety
 *
 * A key is written to a fresh, owner-only temporary file `device-key.jwk.<random>.tmp`, forced to the
 * device, renamed over `device-key.jwk`, and then the directory is forced, so the key file always
 * holds a whole key: the old one until the rename, the new one after. A crash leaves at most a
 * temporary file, which nothing reads and the next key write deletes. On Windows the directory cannot
 * be forced and the rename rests on NTFS's journal, as [FileChannelDurableStore] describes; and there
 * the key file has no POSIX mode, so it takes the directory's access-control list.
 *
 * ## Several stores over one directory
 *
 * Every call takes an exclusive lock on `device-key.lock` in the directory, through a mutex shared by
 * every store over that directory in this process and a [FileChannel.lock] across processes, and
 * reads the files afresh inside it. So two stores over one directory — two `platform` calls, or two
 * processes — are one device: the first to make a key makes the only key, and neither loses the
 * grants the other kept. A store remembers the key it read, so a key replaced by another store
 * afterwards is seen at the next launch, not at once.
 *
 * Safe under a multi-threaded dispatcher. File I/O, and waiting for another process's lock, runs on
 * the caller's dispatcher, as [FileChannelDurableStore]'s does; it is three small files.
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
    private val lockFile: Path = this.directory.resolve(LOCK_FILE)

    /** Shared by every store over this directory in this process; [FileChannel.lock] orders processes. */
    private val inProcess: Mutex = DIRECTORY_MUTEXES.computeIfAbsent(this.directory.toRealPath()) { Mutex() }

    @Volatile
    private var key: DeviceKey? = null

    /** Guarded by [inProcess]: one warning per store for an unreadable grants record. */
    private var warnedAboutGrants = false

    override suspend fun deviceKey(): DeviceKey = key ?: exclusively { key ?: readOrMake().also { key = it } }

    override suspend fun grants(): List<Grant> = exclusively { readGrants() }

    override suspend fun keepGrant(grant: Grant): Unit = exclusively {
        val current = readGrants()
        val next = current.keeping(grant)
        if (next !== current) grantStore.write(GRANTS, StoredGrants.encode(next))
    }

    private suspend fun <T> exclusively(block: suspend () -> T): T = inProcess.withLock {
        FileChannel.open(lockFile, setOf(CREATE, WRITE), *ownerOnly()).use { channel ->
            channel.lock().use { block() }
        }
    }

    private suspend fun readOrMake(): DeviceKey {
        val stored = try {
            Files.readAllBytes(keyFile)
        } catch (_: NoSuchFileException) {
            return make()
        }
        val read = try {
            SoftwareDeviceKey.readBack(stored)
        } finally {
            stored.fill(0)
        }
        return when (read) {
            is StoredKey.Usable -> read.key
            is StoredKey.Refused -> {
                val aside = setAside()
                logger.warn {
                    "kuilt-identity: the device key in $keyFile cannot be used: ${read.reason}. It is kept as " +
                        "$aside and replaced with a new key, so this device is a new device to every room it " +
                        "returns to."
                }
                make()
            }
        }
    }

    /** Moves the refused key file to the first free `device-key.jwk.refused-<n>`. */
    private fun setAside(): Path {
        val target = generateSequence(1) { it + 1 }
            .map { directory.resolve("$KEY_FILE.refused-$it") }
            .first { !Files.exists(it, NOFOLLOW_LINKS) }
        Files.move(keyFile, target, ATOMIC_MOVE)
        return target
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

    /**
     * Clear any crash's leftovers, write a fresh owner-only temporary file, force it, rename it over
     * the key, force the directory. Runs under the lock, so no other writer's temporary file is live.
     */
    private fun writeKeyFile(jwk: ByteArray) {
        Files.newDirectoryStream(directory, "$KEY_FILE*.tmp").use { leftovers -> leftovers.forEach(Files::deleteIfExists) }
        val temp = Files.createTempFile(directory, "$KEY_FILE.", ".tmp", *ownerOnly())
        var renamed = false
        try {
            FileChannel.open(temp, setOf(WRITE)).use { channel ->
                val buffer = ByteBuffer.wrap(jwk)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, keyFile, ATOMIC_MOVE, REPLACE_EXISTING)
            renamed = true
        } finally {
            if (!renamed) Files.deleteIfExists(temp)
        }
        if (!ON_WINDOWS) FileChannel.open(directory, READ).use { it.force(true) }
    }

    private fun ownerOnly(): Array<FileAttribute<*>> =
        if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
            arrayOf(PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE)))
        } else {
            emptyArray()
        }

    private suspend fun readGrants(): List<Grant> {
        val record = grantStore.read(GRANTS) ?: return emptyList()
        return StoredGrants.decode(record) ?: emptyList<Grant>().also {
            if (!warnedAboutGrants) {
                warnedAboutGrants = true
                logger.warn {
                    "kuilt-identity: the grants kept in $directory cannot be read and are dropped; this device " +
                        "returns to each room as an ordinary admission until it is granted again."
                }
            }
        }
    }

    internal companion object {
        /** The key file's name in the store's directory. */
        const val KEY_FILE: String = "device-key.jwk"

        /** The file every call locks, so stores over one directory take turns. */
        const val LOCK_FILE: String = "device-key.lock"

        /** The [StoreKey] the grants record is kept under. */
        val GRANTS: StoreKey = StoreKey("grants")

        private val ON_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        /** One mutex per directory (by real path) for the life of the process; a handful of entries. */
        private val DIRECTORY_MUTEXES = ConcurrentHashMap<Path, Mutex>()
    }
}

/**
 * This device's key store on the desktop JVM: a [FileDeviceKeyStore] over `directory/appId`, so apps
 * sharing one parent directory are different devices to each other. Calling it twice with the same
 * arguments gives two stores that are one device.
 *
 * Pass a directory that outlives the app, that only this user can read, and that **does not travel to
 * another machine** — kuilt does not guess one. A key copied to a second machine makes two devices one
 * device, so avoid what backup, migration and roaming copy across:
 * - **Windows:** under `%LOCALAPPDATA%`, never the roaming `%APPDATA%`, which a domain profile copies to
 *   every machine the user signs in to.
 * - **Linux:** under `$XDG_STATE_HOME` (by default `~/.local/state`), not `$XDG_CONFIG_HOME`, which
 *   people sync between machines.
 * - **macOS:** under `~/Library/Application Support/<app>`, the folder the native macOS store uses.
 *   Time Machine backs it up and Migration Assistant copies it to a new Mac, which is then one device
 *   with the old one until one of them deletes its key file. Excluding it from Time Machine
 *   (`tmutil addexclusion`) stops a migration from that backup carrying the key, but also loses the
 *   key when this same Mac is restored, and does not stop a direct Mac-to-Mac migration; which to
 *   prefer is the app's call.
 *
 * Opening it again with the same [appId] and [directory] after a restart is the same device.
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
