@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.F_OK
import platform.posix.R_OK
import platform.posix.access
import platform.posix.chmod
import platform.posix.errno
import platform.posix.memcpy
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.store.NSFileManagerDurableStore
import us.tractat.kuilt.store.StoreKey

/** A new, empty directory under the temporary directory, named [prefix] plus a fresh UUID. */
internal fun freshDirectory(prefix: String): String {
    val path = NSTemporaryDirectory().trimEnd('/') + "/$prefix-${NSUUID().UUIDString}"
    check(NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)) {
        "precondition: could not create $path"
    }
    return path
}

/** The bytes of the file at [path], or `null` when there is none (or it cannot be read). */
internal fun readFile(path: String): ByteArray? = NSData.create(contentsOfFile = path)?.toBytes()

/** [bytes] as the whole of the file at [path], replacing whatever is there. */
internal fun writeFile(path: String, bytes: ByteArray) {
    check(NSFileManager.defaultManager.createFileAtPath(path, contents = bytes.toData(), attributes = null)) {
        "precondition: could not write $path"
    }
}

/** A fresh app id, so each fixture's Keychain service and directory are its own. */
internal fun freshAppId(): String = "test-${NSUUID().UUIDString}"

private fun ByteArray.toData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toBytes(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}

/**
 * Every permission taken off the grants record in [directory], so opening it fails with `EACCES`, while
 * the directory still takes a new file, so a store that read the failure as "no grants" could still
 * write a new record over it. On an iPhone it stands in for the grants file's protection class keeping
 * it closed after a restart until the first unlock; the store throws on any failed read but a missing
 * file, so the errno is not what the property turns on.
 *
 * The rig checks that it took: run as root, a mode of `000` is not enforced. Its probe reads the record
 * through [NSFileManagerDurableStore] directly, the medium the store keeps it in, and its write probe
 * keeps a sibling record in [directory] the same way, the witness that the directory is still
 * writable. Restoring answers
 * whether the record was written while it was unreadable: a write renames a new, readable file into
 * place, and a delete leaves none, while the faulted file stays unreadable until the restore.
 */
internal fun unreadableGrantsRecord(directory: String): DeviceKeyStoreFixture.UnreadableGrants {
    // `grants` needs no escaping, so the record's filename is the key's name.
    val record = "$directory/${AppleDeviceKeyStore.GRANTS.name}"
    check(access(record, F_OK) == 0) { "precondition: a grants record at $record to fault (errno=$errno)" }
    check(chmod(record, NO_PERMISSIONS.convert()) == 0) { "rig: could not chmod $record (errno=$errno)" }
    check(access(record, R_OK) != 0) { "rig: $record is still readable after chmod 000 — running as root? The fault did not take" }
    return DeviceKeyStoreFixture.UnreadableGrants(
        probe = { NSFileManagerDurableStore(directory).read(AppleDeviceKeyStore.GRANTS) },
        probeWrite = { NSFileManagerDurableStore(directory).write(PROBE_WRITE, byteArrayOf(1)) },
        restore = {
            val present = access(record, F_OK) == 0
            val rewritten = !present || access(record, R_OK) == 0
            if (present) check(chmod(record, OWNER_READ_WRITE.convert()) == 0) { "rig: could not restore $record (errno=$errno)" }
            rewritten
        },
    )
}

/** A record beside the grants, written only by [unreadableGrantsRecord]'s write probe. */
private val PROBE_WRITE = StoreKey("probe-write")

/** `---------`: the grants record may not be opened at all — [unreadableGrantsRecord]'s rig. */
private const val NO_PERMISSIONS = 0

/** `rw-------`: the grants record restored, readable and writable by its owner. */
private const val OWNER_READ_WRITE = 0x180
