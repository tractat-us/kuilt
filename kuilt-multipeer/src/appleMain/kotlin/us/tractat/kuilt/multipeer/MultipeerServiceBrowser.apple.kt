package us.tractat.kuilt.multipeer

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * iOS browser that discovers peers advertising under [factory]'s service
 * type. Each collector receives the current peers, then live `foundPeer` callbacks.
 *
 * The browser does **not** own its own `MCNearbyServiceBrowser` — it
 * activates the one held by [factory] through
 * `MultipeerPeerLinkFactory.startBrowsing`. That same browser instance is
 * later reused by `MultipeerPeerLinkFactory.join` to call `invitePeer`;
 * Apple's MC framework requires the invitation to be sent on the browser
 * that discovered the peer (the peer lives in that browser's internal
 * "peers dictionary"). A throwaway browser created elsewhere would silently
 * drop the invite.
 *
 * The browser uses each peer's `displayName` as the
 * [MultipeerAdvertisement.handle]. Two devices sharing a *human* name do not
 * collide there: what a kuilt peer advertises is the decorated name, whose suffix
 * is that peer's own `selfId` (see `MultipeerPeerId`), so the handles differ even
 * when the lobby shows two identical labels. Disambiguating the two *labels* for a
 * human reader remains a UX problem; the transport needs no de-duplication.
 *
 * Both feeds share one collection-owned browse session. The first collector starts
 * browsing and the last collector stops it. Subscribers attach before browsing starts,
 * so a callback delivered during startup can reach its collector.
 */
@OptIn(ExperimentalForeignApi::class)
public actual class MultipeerServiceBrowser actual constructor(
    private val factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    public actual override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    private val lock = reentrantLock()
    private var collectors = 0

    /** Emits the departed peer's displayName, also used by [MultipeerAdvertisement.peerKey]. */
    public actual override fun departures(): Flow<String> = sessionFeed(factory.lostPeerHandles)

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
                // onSubscription runs AFTER the SharedFlow subscriber is registered. onStart
                // would lose a callback emitted synchronously by startBrowsing.
                lock.withLock {
                    if (collectors == 0) factory.startBrowsing { /* discoveries read the ordered snapshot/event stream */ }
                    collectors++
                    acquired = true
                    onAcquired()
                }
            })
        } finally {
            if (acquired) lock.withLock {
                if (--collectors == 0) factory.stopBrowsing()
            }
        }
    }
}
