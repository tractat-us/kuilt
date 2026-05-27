/*
 * Host-side cdecl exports: starts an MC session in advertiser mode, sends
 * broadcasts, and tears the session down. The JVM consumer
 * (`MultipeerPeerLinkFactory` JVM actual) holds the session handle and forwards
 * `Seam.broadcast` / `close` calls through this surface.
 *
 * Suspension: `runBlocking` is fine here. The Apple-side
 * `MultipeerPeerLinkFactory.open` and `MCSessionLink.broadcast` are
 * `suspend` only by API shape — neither actually yields. Calling
 * `runBlocking` from the JVM-bound thread is just ceremony around an
 * otherwise-synchronous call sequence.
 */
package us.tractat.kuilt.multipeer.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.memcpy
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * Hosts a session: creates an `MCSession`, starts advertising, auto-accepts
 * invitations. Returns an opaque session handle (a
 * `StableRef<BridgeSessionState>.asCPointer()`) that subsequent `mc_session_*`
 * calls accept. Returns null if [handle] is null or the open call fails
 * (e.g. another session is already active for this factory).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_runtime_open")
@Suppress("ktlint:standard:function-naming")
public fun mc_runtime_open(handle: COpaquePointer?): COpaquePointer? {
    if (handle == null) return null
    val factory = handle.asStableRef<MultipeerPeerLinkFactory>().get()
    val link =
        runCatching {
            runBlocking { factory.open(Pattern(displayName = factory.displayName)) }
        }.getOrElse { return null }
    return StableRef.create(BridgeSessionState(link)).asCPointer()
}

/**
 * Closes the session, cancels any data/peer-state pumps, and disposes the
 * handle. Idempotent only across `null`; passing the same non-null pointer
 * twice is a use-after-free.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_session_close")
@Suppress("ktlint:standard:function-naming")
public fun mc_session_close(session: COpaquePointer?) {
    if (session == null) return
    val ref = session.asStableRef<BridgeSessionState>()
    val state = ref.get()
    state.cancelPumps()
    runCatching { runBlocking { state.link.close() } }
    ref.dispose()
}

/**
 * Broadcasts a payload to every connected peer in the session. Returns the
 * number of bytes sent (== [len] on success), or `-1` on null/invalid input.
 *
 * No connected peers is a non-error: returns `len`. The Apple-side
 * `MCSessionLink.broadcast` short-circuits when `connectedPeers` is empty.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_session_broadcast")
@Suppress("ktlint:standard:function-naming")
public fun mc_session_broadcast(
    session: COpaquePointer?,
    data: CPointer<ByteVar>?,
    len: Int,
): Int {
    if (session == null || data == null || len < 0) return -1
    val state = session.asStableRef<BridgeSessionState>().get()
    val bytes = ByteArray(len)
    if (len > 0) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data, len.toULong())
        }
    }
    runCatching { runBlocking { state.link.broadcast(bytes) } }
    return len
}
