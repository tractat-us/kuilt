/*
 * Runtime lifecycle exports for the JVM ↔ macOS K/N MC bridge.
 *
 * The handle returned by `mc_runtime_create` is an opaque pointer the JVM
 * stores and passes back to subsequent calls. Internally it is a
 * `StableRef<MultipeerPeerLinkFactory>`; the ref roots the factory so K/N's
 * GC won't reclaim it while JVM still holds the pointer.
 *
 * Conventions:
 *  - C strings are UTF-8, NUL-terminated (`CPointer<ByteVar>` / `toKString`).
 *  - Output buffers carry their own length; the function returns the number
 *    of bytes written (excluding the trailing NUL) or `-1` if the buffer is
 *    too small. Callers can probe by passing `bufLen = 0` to get the size.
 *  - Errors prior to handle creation surface as a null return; once a handle
 *    exists, errors come back as a negative result code from the relevant
 *    call.
 */
package us.tractat.kuilt.multipeer.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * Creates a `MultipeerPeerLinkFactory` and returns an opaque handle.
 *
 * Returns null if either string argument is null. Once handed out, the
 * caller is responsible for calling [mc_runtime_destroy] exactly once, or
 * the underlying ObjC objects (`MCPeerID`, `MCSession`, …) leak.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_runtime_create")
@Suppress("ktlint:standard:function-naming")
public fun mc_runtime_create(
    displayName: CPointer<ByteVar>?,
    serviceType: CPointer<ByteVar>?,
): COpaquePointer? {
    val name = displayName?.toKString() ?: return null
    val service = serviceType?.toKString() ?: return null
    val factory = MultipeerPeerLinkFactory(displayName = name, serviceType = service)
    return StableRef.create(factory).asCPointer()
}

/**
 * Releases the handle. Calls `factory.close()` first so the
 * `MCNearbyServiceAdvertiser` stops broadcasting and any active session
 * is disconnected; otherwise an advertiser keeps showing up in nearby
 * lobbies long after the host process thinks it has shut down.
 *
 * Safe to call once per handle; double-destroy is a use-after-free and
 * the caller's responsibility to avoid.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_runtime_destroy")
@Suppress("ktlint:standard:function-naming")
public fun mc_runtime_destroy(handle: COpaquePointer?) {
    val ref = handle?.asStableRef<MultipeerPeerLinkFactory>() ?: return
    runCatching { ref.get().close() }
    ref.dispose()
}

/**
 * Stops advertising and disconnects any active session, but keeps the
 * runtime handle alive (so callers can re-open against the same factory
 * without re-creating its `MCPeerID`). For full lifecycle cleanup, pair
 * with [mc_runtime_destroy].
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_runtime_close")
@Suppress("ktlint:standard:function-naming")
public fun mc_runtime_close(handle: COpaquePointer?) {
    val ref = handle?.asStableRef<MultipeerPeerLinkFactory>() ?: return
    runCatching { ref.get().close() }
}

/**
 * Copies the runtime's display name (UTF-8) into [buf], NUL-terminated.
 * Returns the number of bytes written (excluding the trailing NUL), or
 * `-1` if the buffer is too small. Pass `bufLen = 0` to query the required
 * size — the function still returns `-1` but the (size + 1) is implied by
 * the underlying display name's length.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_runtime_display_name")
@Suppress("ktlint:standard:function-naming")
public fun mc_runtime_display_name(
    handle: COpaquePointer?,
    buf: CPointer<ByteVar>?,
    bufLen: Int,
): Int {
    if (handle == null || buf == null) return -1
    val factory = handle.asStableRef<MultipeerPeerLinkFactory>().get()
    val bytes = factory.displayName.encodeToByteArray()
    if (bytes.size + 1 > bufLen) return -1
    bytes.usePinned { pinned ->
        memcpy(buf, pinned.addressOf(0), bytes.size.toULong())
    }
    buf[bytes.size] = 0
    return bytes.size
}
