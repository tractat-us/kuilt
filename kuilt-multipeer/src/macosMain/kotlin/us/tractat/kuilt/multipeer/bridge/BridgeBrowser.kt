/*
 * Browser-side cdecl exports for the JVM ↔ macOS MC bridge.
 *
 * Three exports:
 *  - mc_browser_start subscribes to the apple `MultipeerServiceBrowser` flow
 *    and forwards every `peerFound` emission to a JVM callback, or returns null
 *    if the runtime already has a browse session. The JVM callback is a cdecl
 *    function pointer (JNA `Callback` interface) that receives two NUL-terminated
 *    UTF-8 C strings.
 *  - mc_browser_set_peer_lost_callback registers a second JVM callback that
 *    receives one NUL-terminated UTF-8 handle string for every `peerLost`
 *    event the apple `BrowserDelegate` sees.
 *  - mc_browser_stop cancels the K/N coroutines pumping both flows, waits for
 *    them to finish, and disposes the StableRef.
 *
 * Threading: JNA callback trampolines auto-attach the calling thread to
 * the JVM, so emitting from K/N's `Dispatchers.Default` is safe. A collector
 * is inside the JVM for as long as its callback runs, which cancellation
 * cannot interrupt; that is why mc_browser_stop has to wait.
 *
 * The found collector starts on the thread that called mc_browser_start and
 * stays there up to its first suspension, so `startBrowsing` runs on the JNA
 * caller's thread. Everything after that first suspension runs on `Dispatchers.Default`.
 */
package us.tractat.kuilt.multipeer.bridge

import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.PumpFailure
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.multipeer.MultipeerAdvertisement
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.multipeer.MultipeerServiceBrowser
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

private val log = KotlinLogging.logger("us.tractat.kuilt.multipeer.bridge.BridgeBrowser")

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

    /**
     * Cancels every collector in [scope] and blocks the calling thread until each has finished: out of
     * any JVM callback it was inside, and through the `finally` that releases the factory's browser.
     *
     * Unbounded on purpose. A bound would return with a callback possibly still running, and the
     * caller may then release that callback's JNA trampoline. A callback that never returns therefore
     * hangs this call rather than risking a jump through freed memory.
     */
    fun cancelAndAwaitCollectors() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }
}

/**
 * `onFailure` for a bridge pump. The log line records why. [mc_browser_start] tells the JVM about the
 * failure that matters, `startBrowsing` refusing a second owner of the runtime, by returning null. A
 * failure any later reaches only this line, since no call is left to report it through; nothing in these
 * flows is known to raise one.
 */
private fun reportPumpFailure(pump: String): (PumpFailure, Throwable) -> PumpVerdict =
    { phase, failure ->
        log.error(failure) { "mc.browse.pump.failed pump=$pump phase=$phase" }
        PumpVerdict.CONTINUE
    }

/**
 * Starts an MC browse session for the given runtime and forwards every
 * `peerFound` event to [peerFoundCb]. Returns an opaque browser handle
 * the JVM passes to [mc_browser_set_peer_lost_callback] / [mc_browser_stop].
 *
 * Returns null if any required pointer is null, or if the runtime already has a browse session. A
 * refused start leaves that session running, and the caller gets no handle because nothing is left to
 * stop: the collector has already ended. The check is what makes the refusal synchronous, so it rests
 * on the collector starting undispatched, and on `startBrowsing` running before the flow first suspends.
 * The flow is a `SharedFlow`, which never completes, so a collector that has already finished here failed.
 *
 * A callback can fire inside this call, on the caller's thread, if the flow replays a peer.
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
    val found = browser.discoveries().pumpIn(
        scope = state.scope,
        onFailure = reportPumpFailure("found"),
        name = "mc-browser-found",
        start = CoroutineStart.UNDISPATCHED,
    ) { ad ->
        if (ad is MultipeerAdvertisement) {
            memScoped {
                peerFoundCb.invoke(ad.handle.cstr.ptr, ad.sessionName.cstr.ptr)
            }
        }
    }
    if (found.isCompleted) {
        state.scope.cancel()
        return null
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
    state.factory.lostPeerHandles.pumpIn(state.scope, reportPumpFailure("lost"), "mc-browser-lost") { handle ->
        memScoped {
            peerLostCb.invoke(handle.cstr.ptr)
        }
    }
}

/**
 * Stops the browse session and disposes the handle, returning only once the session's collectors have
 * finished. On return no callback of this session is running or will run, so the JVM may release both,
 * and the factory's browser has been released, so a new session can start at once.
 *
 * It blocks the caller for as long as a callback in flight takes, so calling it from inside one of this
 * session's callbacks deadlocks. Use-after-free if called twice with the same pointer.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("mc_browser_stop")
@Suppress("ktlint:standard:function-naming")
public fun mc_browser_stop(browserHandle: COpaquePointer?) {
    if (browserHandle == null) return
    val ref = browserHandle.asStableRef<BrowserState>()
    ref.get().cancelAndAwaitCollectors()
    ref.dispose()
}
