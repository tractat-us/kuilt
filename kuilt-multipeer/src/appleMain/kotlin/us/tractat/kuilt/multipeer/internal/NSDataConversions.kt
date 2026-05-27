package us.tractat.kuilt.multipeer.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * `ByteArray` → `NSData` for handing payloads to MultipeerConnectivity.
 *
 * Empty arrays produce a non-null empty NSData (matching MC's behaviour for
 * empty messages). The pinned copy is one-shot; NSData makes its own backing
 * buffer.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }

/**
 * `NSData` → `ByteArray` for surfacing MC-received payloads to the rest of
 * the engine. Copies; NSData ownership is unchanged.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
