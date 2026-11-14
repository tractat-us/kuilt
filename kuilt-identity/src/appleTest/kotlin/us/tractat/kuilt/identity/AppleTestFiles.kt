@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.memcpy

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
