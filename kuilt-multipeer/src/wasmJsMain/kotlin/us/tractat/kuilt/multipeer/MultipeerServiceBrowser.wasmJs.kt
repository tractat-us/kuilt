package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * wasmJs unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on wasmJs.
 */
public actual class MultipeerServiceBrowser actual constructor(
    private val factory: MultipeerPeerLinkFactory,
) : PeerDiscoverySource {
    public actual override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    public actual override fun discoveries(): Flow<Tag> =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on wasmJs")

    /**
     * **No leave signal, and none discarded** — the same verdict, for the same reason, as the
     * androidMain stub's `departures()`. A browser has to browse before it can lose anybody, and
     * [discoveries] here refuses.
     *
     * Empty rather than throwing, so a consumer that folds a list of sources through
     * `discoveryRoster` fails on the one call that is genuinely unsupported ([discoveries]) instead
     * of on two.
     *
     * No `DiscoverySourceConformanceSuite` binds this class either: a `causeArrival` for a source
     * whose [discoveries] throws could only be a no-op, and a suite driven by an untrue hook passes
     * by not running. `MultipeerWasmJsStubTest` pins the stronger statement — [discoveries]
     * refuses, and this flow completes at once having emitted nothing.
     */
    public actual override fun departures(): Flow<String> = emptyFlow()
}
