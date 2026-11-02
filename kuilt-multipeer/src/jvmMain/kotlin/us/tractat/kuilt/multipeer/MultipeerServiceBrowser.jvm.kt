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
 * collectors of these two feeds off the smaller Darwin GCD callback stack, including
 * collectors using an unconfined dispatcher. Keep the `flow { emitAll(...) }` boundary
 * here. [MultipeerPeerLinkFactory.visiblePeers] has no such boundary.
 *
 * **One owner per factory:** the bridge allows one browse session per runtime, and each
 * browser has its own lifecycle lock, so nothing serialises two browsers on one factory.
 * What a second browser gets depends on when it starts relative to the first one's teardown:
 *  - **Before the native stop has released the browser:** it is refused. Its collector fails
 *    with `IllegalStateException` and the first session carries on untouched.
 *  - **After the native stop, before the first browser clears the factory's peers:** it starts,
 *    and stays silently deaf. It captured the generation that clear then ends, so the factory
 *    discards every callback it gets. #3071 tracks this.
 *  - **After that:** it starts as normal.
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
                        // That call returns only once no callback of this session is running.
                        Reference.reachabilityFence(stopped)
                        factory.clearVisiblePeers()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // Called with lock held; callbacks only publish to thread-safe flows and the factory snapshot,
    // and never take this lock. A synchronous callback cannot deadlock session startup, and shutdown
    // depends on the same thing: `mc_browser_stop` blocks under this lock until every callback in
    // flight has returned. A blocking unconfined `visiblePeers` collector breaks that, since it runs
    // inside the callback.
    private fun startSession(): BrowseSession? {
        val lib = loadLib() ?: return null
        // Teardown advances the generation under this lock, so this is the generation the session
        // runs in. `mc_browser_stop` waits for in-flight callbacks, so none should land after this
        // session has stopped; carrying the generation makes the factory discard one if it does.
        val session = factory.discoveryUpdates.generation
        val found = MultipeerNativeLib.PeerFoundCallback { handle, displayName ->
            val ad = MultipeerAdvertisement(handle, displayName, factory.serviceType)
            factory.setVisiblePeer(session, handle, ad)
        }
        val lost = MultipeerNativeLib.PeerLostCallback { handle ->
            if (factory.setVisiblePeer(session, handle, advertisement = null)) departuresFlow.tryEmit(handle)
        }
        // Null means another browser on this factory holds the runtime's session. Throwing here leaves
        // `collectors` unmoved, so this browser's teardown never runs and cannot clear that session's peers.
        val handle = checkNotNull(lib.mc_browser_start(factory.requireRuntimeHandle(), found)) {
            "MultipeerPeerLinkFactory is already browsing"
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
