package us.tractat.kuilt.multipeer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * JVM-side `MultipeerServiceBrowser`, backed by [MultipeerNativeLib] and
 * the macOS K/N `libkuilt.dylib`.
 *
 * `discoveries()` returns a cold `Flow` that opens a real MC browse
 * session under the covers, forwards every `foundPeer` event from the
 * dylib, and shuts the session down when the collector cancels. `peerLost`
 * events surface via `departures()` — a [MutableSharedFlow] that the
 * `peerLost` callback feeds while the browse session is active.
 *
 * Non-macOS hosts (Linux/Windows): both flows are empty and never throw —
 * the lobby just shows no MC peers, while still receiving full mDNS
 * results via the parallel mDNS source.
 *
 * **Single-collector constraint:** the underlying dylib supports one active
 * browse session at a time. Do not collect `discoveries()` and `departures()`
 * concurrently from multiple coroutines.
 *
 * **Threading:** [discoveries] applies `.flowOn(Dispatchers.IO)` so that the
 * JNA callback's `trySend` call never inline-resumes the downstream collector
 * on the Darwin GCD thread that fired the callback.
 *
 * Darwin GCD worker threads have a 544 K stack + 16 K guard. The K/N dylib
 * runs its browser-collect coroutine on GCD and then calls the JNA
 * `peerFoundCb` from that GCD thread. With a deep JVM operator chain above
 * the browser, the JVM JNI frames (executed on the GCD stack) overflow the
 * guard page when `trySend` inline-resumes the suspended downstream collector.
 *
 * `.flowOn(IO)` decouples the sender from the downstream receiver by routing
 * collection through a JVM IO thread with a standard 1 MB+ stack, ensuring
 * the collection chain never runs on the GCD thread.
 */
public actual class MultipeerServiceBrowser actual constructor(
    private val factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    /**
     * Internal constructor for unit tests: injects a custom [libLoader] so
     * tests can exercise threading behaviour without loading the real dylib.
     */
    internal constructor(
        factory: MultipeerPeerLinkFactory,
        libLoader: () -> MultipeerNativeLib?,
    ) : this(factory) {
        this.libLoaderOverride = libLoader
    }

    // Set from the test constructor before any use of [loadLib].
    private var libLoaderOverride: (() -> MultipeerNativeLib?)? = null

    private fun loadLib(): MultipeerNativeLib? = libLoaderOverride?.invoke() ?: MultipeerNativeLib.load()

    public actual override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    private val departuresFlow: MutableSharedFlow<String> = MutableSharedFlow(replay = 0, extraBufferCapacity = 16)

    /**
     * Peer keys for peers that have left the network since [discoveries] started
     * collecting. Only emits while the [discoveries] flow is being collected.
     */
    override fun departures(): Flow<String> = departuresFlow.asSharedFlow()

    public actual override fun discoveries(): Flow<Tag> =
        flow {
            emitAll(
                callbackFlow {
                    val lib = loadLib()
                    if (lib == null) {
                        // Non-macOS hosts: silently emit nothing.
                        awaitClose { /* no-op */ }
                        return@callbackFlow
                    }
                    val runtimeHandle = factory.requireRuntimeHandle()

                    // JNA requires a strong JVM reference to both callback objects for
                    // the lifetime of the browse session, otherwise the trampoline can
                    // be GC'd and the K/N side will SIGSEGV next time it fires.
                    val foundCallback =
                        MultipeerNativeLib.PeerFoundCallback { handle, displayName ->
                            val ad =
                                MultipeerAdvertisement(
                                    handle = handle,
                                    displayName = displayName,
                                    serviceType = factory.serviceType,
                                )
                            factory.setVisiblePeer(handle, ad)
                            trySend(ad)
                        }
                    val lostCallback =
                        MultipeerNativeLib.PeerLostCallback { handle ->
                            factory.setVisiblePeer(handle, advertisement = null)
                            departuresFlow.tryEmit(handle)
                        }
                    val browser = lib.mc_browser_start(runtimeHandle, foundCallback)
                    if (browser == null) {
                        close(IllegalStateException("mc_browser_start returned null on macOS"))
                        return@callbackFlow
                    }
                    lib.mc_browser_set_peer_lost_callback(browser, lostCallback)

                    awaitClose {
                        // The callback strong-refs die with this lambda's closure
                        // capture because `foundCallback` / `lostCallback` are defined
                        // inside `callbackFlow {}` — keeping the references until the
                        // flow completes.
                        lib.mc_browser_stop(browser)
                        // mc_browser_stop doesn't fire peerLost for in-range peers;
                        // drop the snapshot so the next collection starts clean.
                        factory.clearVisiblePeers()
                    }
                },
            )
        }.flowOn(Dispatchers.IO)
}
