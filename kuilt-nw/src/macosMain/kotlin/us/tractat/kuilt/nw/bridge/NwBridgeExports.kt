/*
 * cdecl exports for the JVM ↔ macOS Network.framework bridge.
 *
 * The handle returned by `nw_runtime_create` is an opaque pointer the JVM stores
 * and passes back to every subsequent call. Internally it is a
 * `StableRef<NwBridgeRuntime>`; the ref roots the runtime so K/N's GC won't
 * reclaim it while the JVM still holds the pointer.
 *
 * Conventions:
 *  - C strings are UTF-8, NUL-terminated (`CPointer<ByteVar>` / `toKString`).
 *  - Byte buffers are `(CPointer<ByteVar>, Int len)`; the K/N side copies the
 *    bytes out with `memcpy` before the pointer goes stale.
 *  - Suspend `RealNwApi` ops are driven via `runBlocking` on the JNA calling
 *    thread and return an `Int` result code: `0` on success, `<0` on error.
 *  - Callback registration takes a cdecl function pointer (a JNA `Callback`);
 *    the JVM holds the strong reference that keeps the trampoline alive.
 *
 * Threading: JNA callback trampolines auto-attach the firing thread to the JVM,
 * so invoking the callbacks from K/N's `Dispatchers.Default` collectors is safe.
 */
package us.tractat.kuilt.nw.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.memcpy
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.nw.NwLoopbackConfig
import us.tractat.kuilt.nw.NwLoopbackRendezvous
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

// ── lifecycle ────────────────────────────────────────────────────────────────

/**
 * Builds an [NwBridgeRuntime] wrapping a `RealNwApi(NwPskMaterial(psk, identity))`
 * and returns its opaque handle. The [psk]/[identity] buffers carry their own
 * lengths; both are copied into K/N-owned byte arrays here. Returns `null` on bad
 * args (null pointers or negative lengths). Pair every successful create with
 * exactly one [nw_runtime_destroy] — double-destroy is a use-after-free.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_runtime_create")
@Suppress("ktlint:standard:function-naming")
public fun nw_runtime_create(
    psk: CPointer<ByteVar>?,
    pskLen: Int,
    identity: CPointer<ByteVar>?,
    identityLen: Int,
): COpaquePointer? {
    if (psk == null || identity == null || pskLen < 0 || identityLen < 0) return null
    val pskBytes = if (pskLen == 0) ByteArray(0) else psk.readBytes(pskLen)
    val identityBytes = if (identityLen == 0) ByteArray(0) else identity.readBytes(identityLen)
    val runtime = NwBridgeRuntime(psk = pskBytes, identity = identityBytes)
    return StableRef.create(runtime).asCPointer()
}

/**
 * Gracefully tears the runtime down (stop listening/browsing, disconnect all) and
 * disposes the StableRef. Safe to call once per handle; double-destroy is a
 * use-after-free and the caller's responsibility to avoid.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_runtime_destroy")
@Suppress("ktlint:standard:function-naming")
public fun nw_runtime_destroy(handle: COpaquePointer?) {
    val ref = handle?.asStableRef<NwBridgeRuntime>() ?: return
    runCatchingCancellable { ref.get().destroy() }
    ref.dispose()
}

// ── loopback conformance (JVM↔JVM SeamConformanceSuite over the real dylib) ───

/**
 * Creates an in-process [NwLoopbackRendezvous] and returns its opaque handle. The rendezvous is
 * shared by exactly one host/joiner runtime pair built with [nw_runtime_create_loopback]: the host
 * publishes its OS-assigned bound port into it, the joiner awaits that port before dialling
 * `127.0.0.1:port`. Pair every successful create with exactly one [nw_loopback_rendezvous_destroy].
 * This is the CI-runnable direct-loopback path that lets a JVM↔JVM `SeamConformanceSuite` prove the
 * TLS-PSK link end-to-end through `libkuilt.dylib`, closing the last automated gap below hardware.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_loopback_rendezvous_create")
@Suppress("ktlint:standard:function-naming")
public fun nw_loopback_rendezvous_create(): COpaquePointer? =
    StableRef.create(NwLoopbackRendezvous()).asCPointer()

/**
 * Disposes a [NwLoopbackRendezvous] StableRef. The rendezvous holds only a `CompletableDeferred`, so
 * there is no runtime teardown — just drop the ref. Destroy the host/joiner runtimes (via
 * [nw_runtime_destroy]) BEFORE the rendezvous. Safe to call once per handle; double-destroy is a
 * use-after-free and the caller's responsibility to avoid.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_loopback_rendezvous_destroy")
@Suppress("ktlint:standard:function-naming")
public fun nw_loopback_rendezvous_destroy(handle: COpaquePointer?) {
    handle?.asStableRef<NwLoopbackRendezvous>()?.dispose()
}

/**
 * Builds a direct-loopback [NwBridgeRuntime] — `RealNwApi(NwPskMaterial(psk, identity), loopback)` —
 * over the shared [rendezvous] and returns its opaque handle. Mirrors [nw_runtime_create]'s
 * null/negative-length guards; additionally returns `null` when [rendezvous] is null. [dial] selects
 * the role: `0` = HOST (publishes its bound port, never dials), non-zero = JOINER (awaits the port,
 * then dials). Pair every successful create with exactly one [nw_runtime_destroy].
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_runtime_create_loopback")
@Suppress("ktlint:standard:function-naming")
public fun nw_runtime_create_loopback(
    psk: CPointer<ByteVar>?,
    pskLen: Int,
    identity: CPointer<ByteVar>?,
    identityLen: Int,
    rendezvous: COpaquePointer?,
    dial: Int,
): COpaquePointer? {
    if (psk == null || identity == null || pskLen < 0 || identityLen < 0 || rendezvous == null) return null
    val pskBytes = if (pskLen == 0) ByteArray(0) else psk.readBytes(pskLen)
    val identityBytes = if (identityLen == 0) ByteArray(0) else identity.readBytes(identityLen)
    val rv = rendezvous.asStableRef<NwLoopbackRendezvous>().get()
    val runtime = NwBridgeRuntime(
        psk = pskBytes,
        identity = identityBytes,
        loopback = NwLoopbackConfig(dial = dial != 0, rendezvous = rv),
    )
    return StableRef.create(runtime).asCPointer()
}

// ── callback registration ────────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_set_endpoint_found_callback")
@Suppress("ktlint:standard:function-naming")
public fun nw_set_endpoint_found_callback(handle: COpaquePointer?, cb: CPointer<EndpointFoundCb>?) {
    if (handle == null || cb == null) return
    handle.asStableRef<NwBridgeRuntime>().get().setEndpointFoundCallback(cb)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_set_connection_opened_callback")
@Suppress("ktlint:standard:function-naming")
public fun nw_set_connection_opened_callback(handle: COpaquePointer?, cb: CPointer<ConnectionOpenedCb>?) {
    if (handle == null || cb == null) return
    handle.asStableRef<NwBridgeRuntime>().get().setConnectionOpenedCallback(cb)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_set_bytes_received_callback")
@Suppress("ktlint:standard:function-naming")
public fun nw_set_bytes_received_callback(handle: COpaquePointer?, cb: CPointer<BytesReceivedCb>?) {
    if (handle == null || cb == null) return
    handle.asStableRef<NwBridgeRuntime>().get().setBytesReceivedCallback(cb)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_set_connection_closed_callback")
@Suppress("ktlint:standard:function-naming")
public fun nw_set_connection_closed_callback(handle: COpaquePointer?, cb: CPointer<ConnectionClosedCb>?) {
    if (handle == null || cb == null) return
    handle.asStableRef<NwBridgeRuntime>().get().setConnectionClosedCallback(cb)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_set_connection_closed_state_callback")
@Suppress("ktlint:standard:function-naming")
public fun nw_set_connection_closed_state_callback(handle: COpaquePointer?, cb: CPointer<ConnectionClosedStateCb>?) {
    if (handle == null || cb == null) return
    handle.asStableRef<NwBridgeRuntime>().get().setConnectionClosedStateCallback(cb)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_set_connection_viability_callback")
@Suppress("ktlint:standard:function-naming")
public fun nw_set_connection_viability_callback(handle: COpaquePointer?, cb: CPointer<ConnectionViabilityCb>?) {
    if (handle == null || cb == null) return
    handle.asStableRef<NwBridgeRuntime>().get().setConnectionViabilityCallback(cb)
}

// ── ops (0 ok, <0 error) ─────────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_start_listening")
@Suppress("ktlint:standard:function-naming")
public fun nw_start_listening(
    handle: COpaquePointer?,
    serviceName: CPointer<ByteVar>?,
    serviceType: CPointer<ByteVar>?,
): Int {
    if (handle == null || serviceName == null || serviceType == null) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    return runCatchingCancellable {
        runBlocking { runtime.startListening(serviceName.toKString(), serviceType.toKString()) }
        0
    }.getOrDefault(-1)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_stop_listening")
@Suppress("ktlint:standard:function-naming")
public fun nw_stop_listening(handle: COpaquePointer?): Int {
    if (handle == null) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    return runCatchingCancellable {
        runBlocking { runtime.stopListening() }
        0
    }.getOrDefault(-1)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_start_browsing")
@Suppress("ktlint:standard:function-naming")
public fun nw_start_browsing(handle: COpaquePointer?, serviceType: CPointer<ByteVar>?): Int {
    if (handle == null || serviceType == null) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    return runCatchingCancellable {
        runBlocking { runtime.startBrowsing(serviceType.toKString()) }
        0
    }.getOrDefault(-1)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_stop_browsing")
@Suppress("ktlint:standard:function-naming")
public fun nw_stop_browsing(handle: COpaquePointer?): Int {
    if (handle == null) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    return runCatchingCancellable {
        runBlocking { runtime.stopBrowsing() }
        0
    }.getOrDefault(-1)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_connect")
@Suppress("ktlint:standard:function-naming")
public fun nw_connect(handle: COpaquePointer?, endpointId: CPointer<ByteVar>?): Int {
    if (handle == null || endpointId == null) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    return runCatchingCancellable {
        runBlocking { runtime.connect(endpointId.toKString()) }
        0
    }.getOrDefault(-1)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_disconnect")
@Suppress("ktlint:standard:function-naming")
public fun nw_disconnect(handle: COpaquePointer?, connectionId: CPointer<ByteVar>?): Int {
    if (handle == null || connectionId == null) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    return runCatchingCancellable {
        runBlocking { runtime.disconnect(connectionId.toKString()) }
        0
    }.getOrDefault(-1)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("nw_send")
@Suppress("ktlint:standard:function-naming")
public fun nw_send(
    handle: COpaquePointer?,
    connectionId: CPointer<ByteVar>?,
    data: CPointer<ByteVar>?,
    len: Int,
): Int {
    if (handle == null || connectionId == null || data == null || len < 0) return -1
    val runtime = handle.asStableRef<NwBridgeRuntime>().get()
    val bytes = ByteArray(len)
    if (len > 0) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data, len.toULong()) }
    }
    return runCatchingCancellable {
        runBlocking { runtime.send(connectionId.toKString(), bytes) }
        0
    }.getOrDefault(-1)
}
