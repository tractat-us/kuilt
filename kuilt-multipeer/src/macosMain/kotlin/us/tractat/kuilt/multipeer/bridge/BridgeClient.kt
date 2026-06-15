/*
 * Client-side cdecl exports completing the bidirectional MC bridge.
 *
 * Together with `BridgeHost` (host: open + advertise + broadcast) and
 * `BridgeBrowser` (discover peers), this file gives the JVM:
 *  - mc_runtime_join — invite a discovered peer into a session as the client
 *  - mc_session_set_data_callback — pump incoming frames into the JVM
 *  - mc_session_set_peer_state_callback — pump connect/disconnect events
 *  - mc_session_send_to — addressed send to a single peer
 *
 * Pump coroutines live on `BridgeSessionState.scope` (one per session); they
 * stop when `mc_session_close` cancels the scope, or when a fresh
 * `set_*_callback` call cancels the previous pump and starts a new one.
 *
 * Threading: same as `BridgeBrowser` — JNA's callback trampoline auto-attaches
 * the firing thread to the JVM, so emitting from K/N's `Dispatchers.Default`
 * is safe.
 */
package us.tractat.kuilt.multipeer.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.memcpy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.multipeer.MultipeerAdvertisement
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * `(peerId: char*, data: char*, len: int) -> void` — JNA-side incoming-data
 * callback signature. The data pointer is valid only for the duration of
 * the call; copy bytes out immediately.
 */
@OptIn(ExperimentalForeignApi::class)
private typealias DataCallback = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?, Int) -> Unit>

/**
 * `(peerId: char*, isConnected: int) -> void` — JNA-side peer-state callback.
 * `isConnected = 1` for connect, `0` for disconnect.
 */
@OptIn(ExperimentalForeignApi::class)
private typealias PeerStateCallback = CFunction<(CPointer<ByteVar>?, Int) -> Unit>

/**
 * Joins a discovered peer's session as the client. The peer must have been
 * surfaced earlier through `mc_browser_start` so the apple factory's
 * known-peer map can resolve [peerHandle] back to its `MCPeerID`.
 *
 * Returns an opaque session handle; pair with exactly one `mc_session_close`.
 * Returns null on invalid arguments or if join fails (e.g. unknown handle,
 * factory already has an active session).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_runtime_join")
@Suppress("ktlint:standard:function-naming")
public fun mc_runtime_join(
    runtimeHandle: COpaquePointer?,
    peerHandle: CPointer<ByteVar>?,
): COpaquePointer? {
    if (runtimeHandle == null || peerHandle == null) return null
    val factory = runtimeHandle.asStableRef<MultipeerPeerLinkFactory>().get()
    val handleStr = peerHandle.toKString()
    val advertisement =
        MultipeerAdvertisement(
            handle = handleStr,
            displayName = handleStr,
            serviceType = factory.serviceType,
        )
    val link =
        runCatching {
            runBlocking { factory.join(advertisement) }
        }.getOrElse { return null }
    return StableRef.create(BridgeSessionState(link)).asCPointer()
}

/**
 * Attaches a data callback to the session. Cancels any previously-attached
 * pump and starts a new one collecting `link.incoming` and invoking [cb].
 * No-op if either argument is null.
 *
 * The JVM caller must hold a strong reference to the underlying JNA
 * callback object for the lifetime of the session — same gotcha as
 * `mc_browser_start`.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_session_set_data_callback")
@Suppress("ktlint:standard:function-naming")
public fun mc_session_set_data_callback(
    session: COpaquePointer?,
    cb: CPointer<DataCallback>?,
) {
    if (session == null || cb == null) return
    val state = session.asStableRef<BridgeSessionState>().get()
    state.dataPumpJob?.cancel()
    state.dataPumpJob =
        state.scope.launch {
            state.link.incoming.collect { frame ->
                memScoped {
                    val senderPtr = (frame.sender?.value ?: "").cstr.ptr
                    if (frame.payload.isEmpty()) {
                        val emptyBuf = ByteArray(1).pin()
                        try {
                            cb.invoke(senderPtr, emptyBuf.addressOf(0), 0)
                        } finally {
                            emptyBuf.unpin()
                        }
                    } else {
                        frame.payload.usePinned { pinned ->
                            cb.invoke(senderPtr, pinned.addressOf(0), frame.payload.size)
                        }
                    }
                }
            }
        }
}

/**
 * Attaches a peer-state callback. Cancels any previously-attached pump.
 * Emits one event per peer addition / removal observed on
 * `us.tractat.kuilt.core.Seam.peers`. The local
 * peer's own `selfId` is excluded — only remote peers are reported.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_session_set_peer_state_callback")
@Suppress("ktlint:standard:function-naming")
public fun mc_session_set_peer_state_callback(
    session: COpaquePointer?,
    cb: CPointer<PeerStateCallback>?,
) {
    if (session == null || cb == null) return
    val state = session.asStableRef<BridgeSessionState>().get()
    state.peerStatePumpJob?.cancel()
    state.peerStatePumpJob =
        state.scope.launch {
            var seen: Set<PeerId> = setOf(state.link.selfId)
            state.link.peers.collect { current ->
                val added = current - seen
                val removed = seen - current
                for (peer in added) {
                    if (peer == state.link.selfId) continue
                    memScoped { cb.invoke(peer.value.cstr.ptr, 1) }
                }
                for (peer in removed) {
                    if (peer == state.link.selfId) continue
                    memScoped { cb.invoke(peer.value.cstr.ptr, 0) }
                }
                seen = current
            }
        }
}

/**
 * Sends [data] (first [len] bytes) to a single peer addressed by
 * [peerHandle] (== `MCPeerID.displayName`). Returns the number of bytes
 * sent or `-1` on invalid input. If the peer is no longer connected, the
 * Apple-side `MCSessionLink.sendTo` throws and we return `-1`.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_session_send_to")
@Suppress("ktlint:standard:function-naming")
public fun mc_session_send_to(
    session: COpaquePointer?,
    peerHandle: CPointer<ByteVar>?,
    data: CPointer<ByteVar>?,
    len: Int,
): Int {
    if (session == null || peerHandle == null || data == null || len < 0) return -1
    val state = session.asStableRef<BridgeSessionState>().get()
    val target = PeerId(peerHandle.toKString())
    val bytes = ByteArray(len)
    if (len > 0) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data, len.toULong())
        }
    }
    return runCatching {
        runBlocking { state.link.sendTo(target, bytes) }
        len
    }.getOrDefault(-1)
}
