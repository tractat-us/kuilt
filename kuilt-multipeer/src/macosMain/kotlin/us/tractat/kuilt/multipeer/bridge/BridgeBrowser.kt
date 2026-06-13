/*
 * Browser-side cdecl exports for the JVM ↔ macOS MC bridge.
 *
 * Three exports:
 *  - mc_browser_start subscribes to the apple `MultipeerServiceBrowser` flow
 *    and forwards every `peerFound` emission to a JVM callback. The JVM
 *    callback is a cdecl function pointer (JNA `Callback` interface) that
 *    receives two NUL-terminated UTF-8 C strings.
 *  - mc_browser_set_peer_lost_callback registers a second JVM callback that
 *    receives one NUL-terminated UTF-8 handle string for every `peerLost`
 *    event the apple `BrowserDelegate` sees.
 *  - mc_browser_stop cancels the K/N coroutines pumping both flows and
 *    disposes the StableRef.
 *
 * Threading: JNA callback trampolines auto-attach the calling thread to
 * the JVM, so emitting from K/N's `Dispatchers.Default` is safe. Each call
 * is fire-and-forget — we don't await the JVM's processing.
 */
package us.tractat.kuilt.multipeer.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import us.tractat.kuilt.multipeer.MultipeerAdvertisement
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.multipeer.MultipeerServiceBrowser
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * `(handle: char*, displayName: char*) -> void` — the C signature of the
 * JNA-side `peerFound` callback. JNA represents it as a `Callback`
 * interface; K/N receives it as this `CPointer<CFunction<...>>`.
 */
@OptIn(ExperimentalForeignApi::class)
private typealias PeerFoundCallback = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>

/**
 * `(handle: char*) -> void` — the C signature of the JNA-side `peerLost`
 * callback. Single-string variant of [PeerFoundCallback] because the apple
 * `BrowserDelegate.browser(_:lostPeer:)` only carries an `MCPeerID` (whose
 * `displayName` we use as the handle).
 */
@OptIn(ExperimentalForeignApi::class)
private typealias PeerLostCallback = CFunction<(CPointer<ByteVar>?) -> Unit>

/**
 * Holds the live K/N state behind a JVM browser-handle pointer: the apple
 * factory whose flows we're pumping, plus the coroutine scope that fans
 * `discoveries()` / `lostPeerHandles` into JVM callbacks. The struct exists
 * so `mc_browser_stop` has a single thing to dispose, and so a later
 * `mc_browser_set_peer_lost_callback` can attach a second collector to the
 * same scope.
 *
 * @param dispatcher Dispatcher for the [scope]. Production default is
 *   [Dispatchers.Default]; tests inject [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 */
internal class BrowserState(
    val factory: MultipeerPeerLinkFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())
}

/**
 * Starts an MC browse session for the given runtime and forwards every
 * `peerFound` event to [peerFoundCb]. Returns an opaque browser handle
 * the JVM passes to [mc_browser_set_peer_lost_callback] / [mc_browser_stop].
 *
 * Returns null if any required pointer is null.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_browser_start")
@Suppress("ktlint:standard:function-naming")
public fun mc_browser_start(
    runtimeHandle: COpaquePointer?,
    peerFoundCb: CPointer<PeerFoundCallback>?,
): COpaquePointer? {
    if (runtimeHandle == null || peerFoundCb == null) return null
    val factory = runtimeHandle.asStableRef<MultipeerPeerLinkFactory>().get()
    val browser = MultipeerServiceBrowser(factory)

    val state = BrowserState(factory)
    state.scope.launch {
        browser.discoveries().collect { ad ->
            if (ad is MultipeerAdvertisement) {
                memScoped {
                    peerFoundCb.invoke(ad.handle.cstr.ptr, ad.displayName.cstr.ptr)
                }
            }
        }
    }

    return StableRef.create(state).asCPointer()
}

/**
 * Attaches a `peerLost` callback to the running browse session identified
 * by [browserHandle]. Idempotent only across `null`; calling it twice with
 * non-null arguments registers a second collector — callers should attach
 * the callback once, right after [mc_browser_start].
 *
 * The K/N collector subscribes to `MultipeerPeerLinkFactory.lostPeerHandles`,
 * which the apple `BrowserDelegate` emits to on every `browser(_:lostPeer:)`
 * callback.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_browser_set_peer_lost_callback")
@Suppress("ktlint:standard:function-naming")
public fun mc_browser_set_peer_lost_callback(
    browserHandle: COpaquePointer?,
    peerLostCb: CPointer<PeerLostCallback>?,
) {
    if (browserHandle == null || peerLostCb == null) return
    val state = browserHandle.asStableRef<BrowserState>().get()
    state.scope.launch {
        state.factory.lostPeerHandles.collect { handle ->
            memScoped {
                peerLostCb.invoke(handle.cstr.ptr)
            }
        }
    }
}

/**
 * Stops the browse session, cancels the K/N coroutines, and disposes the
 * handle. Use-after-free if called twice with the same pointer.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_browser_stop")
@Suppress("ktlint:standard:function-naming")
public fun mc_browser_stop(browserHandle: COpaquePointer?) {
    if (browserHandle == null) return
    val ref = browserHandle.asStableRef<BrowserState>()
    ref.get().scope.cancel()
    ref.dispose()
}
