package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * Android unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on Android.
 */
public actual class MultipeerServiceBrowser actual constructor(
    private val factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    public actual override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    public actual override fun discoveries(): Flow<Tag> =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on android")

    /**
     * **No leave signal, and none discarded.** This platform has no MultipeerConnectivity at all,
     * so nothing can ever be discovered here and nothing can ever depart. The discovery survey
     * checked for one: there is no Android-side browse API in this class to hear a `lostPeer` from,
     * because [discoveries] itself refuses.
     *
     * That makes this `emptyFlow()` categorically unlike the four the removal of
     * `PeerDiscoverySource.departures`' default exposed. Those were real leave signals thrown away;
     * this is the absence of a signal, so `discoveryRoster`'s ghost caveat is vacuous here — a
     * source that discovers nobody accumulates no ghosts.
     *
     * Empty rather than throwing, so a consumer that folds a list of sources through
     * `discoveryRoster` fails on the one call that is genuinely unsupported ([discoveries]) instead
     * of on two.
     *
     * **Why no `DiscoverySourceConformanceSuite` binds this class.** Every property in that suite
     * starts from an arrival, and its `causeArrival` hook is non-nullable precisely so no binding
     * can claim one it cannot produce. Here [discoveries] throws, so the only `causeArrival` this
     * class admits is a no-op — an untrue claim that would make all four properties pass by not
     * running, which is the vacuity that suite exists to remove. `MultipeerAndroidStubTest` pins
     * the stronger statement instead: [discoveries] refuses, and this flow completes at once having
     * emitted nothing.
     */
    public actual override fun departures(): Flow<String> = emptyFlow()
}
