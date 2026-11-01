package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import java.lang.ref.Reference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onSubscription
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * JVM-side `MultipeerServiceBrowser`, backed by [MultipeerNativeLib] and
 * the macOS K/N `libkuilt.dylib`.
 *
 * Both feeds share one collection-owned MC browse session. The first collector
 * starts it and the last stops it. Each subscriber is registered before native
 * callbacks can fire. On non-macOS hosts both feeds remain silent.
 *
 * **Threading:** each feed collects its shared flow on `Dispatchers.IO`. A native
 * callback only enqueues an event; the IO coroutine emits it downstream. This keeps
 * JVM collectors off the smaller Darwin GCD callback stack, including collectors
 * using an unconfined dispatcher. Keep the `flow { emitAll(...) }` boundary here.
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

    private val lock = ReentrantLock()
    private var collectors = 0
    private var session: BrowseSession? = null
    private val departuresFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Emits the dylib peer handle, also used by [MultipeerAdvertisement.peerKey]. */
    public actual override fun departures(): Flow<String> = sessionFeed(departuresFlow)

    public actual override fun discoveries(): Flow<Tag> = flow {
        lateinit var generation: MultipeerBrowseGeneration
        emitAll(sessionFeed(factory.discoveryUpdates.events) {
            // A subscriber may have reserved replay while waiting for teardown's lock.
            generation = factory.discoveryUpdates.generation
        }.advertisements { generation })
    }

    private fun <T> sessionFeed(
        events: SharedFlow<T>,
        onAcquired: () -> Unit = {},
    ): Flow<T> = flow {
        var acquired = false
        try {
            emitAll(events.onSubscription {
                lock.withLock {
                    if (collectors == 0) session = startSession()
                    collectors++
                    acquired = true
                    onAcquired()
                }
            })
        } finally {
            if (acquired) lock.withLock {
                if (--collectors == 0) {
                    val stopped = session
                    session = null
                    try {
                        stopped?.let { it.lib.mc_browser_stop(it.handle) }
                    } finally {
                        // JNA retains only weak callback references. Keep both trampolines
                        // alive through the native stop call, including exceptional teardown.
                        Reference.reachabilityFence(stopped)
                        factory.clearVisiblePeers()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // Called with lock held; callbacks only publish to thread-safe flows and the factory snapshot,
    // never take this lock. A synchronous callback cannot deadlock session startup or shutdown.
    private fun startSession(): BrowseSession? {
        val lib = loadLib() ?: return null
        // Teardown advances the generation under this lock, so this is the generation the session
        // runs in. `mc_browser_stop` does not wait for in-flight callbacks; carrying the generation
        // lets the factory discard one that lands after this session has stopped.
        val session = factory.discoveryUpdates.generation
        val found = MultipeerNativeLib.PeerFoundCallback { handle, displayName ->
            val ad = MultipeerAdvertisement(handle, displayName, factory.serviceType)
            factory.setVisiblePeer(session, handle, ad)
        }
        val lost = MultipeerNativeLib.PeerLostCallback { handle ->
            if (factory.setVisiblePeer(session, handle, advertisement = null)) departuresFlow.tryEmit(handle)
        }
        val handle = checkNotNull(lib.mc_browser_start(factory.requireRuntimeHandle(), found)) {
            "mc_browser_start returned null on macOS"
        }
        val started = BrowseSession(lib, handle, found, lost)
        try {
            lib.mc_browser_set_peer_lost_callback(handle, lost)
        } catch (failure: Throwable) {
            try {
                lib.mc_browser_stop(handle)
            } finally {
                Reference.reachabilityFence(started)
                factory.clearVisiblePeers()
            }
            throw failure
        }
        return started
    }

    private class BrowseSession(
        val lib: MultipeerNativeLib,
        val handle: Pointer,
        val found: MultipeerNativeLib.PeerFoundCallback,
        val lost: MultipeerNativeLib.PeerLostCallback,
    )
}
