package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.Tag

/**
 * A transport-agnostic feed of peer advertisements.
 *
 * Each transport module provides its own implementation: `MDNSServiceDiscoverer`
 * for Bonjour, `MultipeerServiceBrowser` for Apple MultipeerConnectivity, and so
 * on. The lobby controller composes a list of these and merges their flows so
 * the UI sees one unified roster.
 *
 * Implementations expose a *narrower* return type via Kotlin's covariant-return
 * support (e.g. `Flow<MDNSAdvertisement>`); direct callers keep their typed
 * APIs while the lobby treats every source as `Flow<Tag>`.
 *
 * Both members are required. Neither has a default, so a source that cannot honour one of them
 * says so in its own body rather than inheriting silence — see [departures].
 *
 * **Not an election input.** A discovery feed is one peer's current best guess at who is around:
 * it lags, it may never remove departed peers (see [departures]), and [Tag.peerKey] is
 * transport-scoped, so the same physical peer carries different keys across sources. Two peers
 * folding these flows can hold different rosters — and so compute different answers — even with
 * perfect connectivity. Pick a host from [us.tractat.kuilt.core.Seam.peers] once connected, not
 * from here. See `docs/discovery-bootstrap.md`.
 */
public interface PeerDiscoverySource {
    /** Identifies the underlying transport (mDNS, MultipeerConnectivity, …). */
    public val kind: DiscoveryKind

    /**
     * Cold flow that emits a [Tag] for every peer the source
     * discovers. Stays open until the collector's scope is cancelled.
     */
    public fun discoveries(): Flow<Tag>

    /**
     * Cold flow that emits a [Tag.peerKey] for every peer that leaves the network.
     *
     * **There is no default, deliberately.** A source that genuinely has no leave signal — a test
     * fake that emits a fixed roster, a platform stub, a browse API that only ever reports
     * arrivals — must return [emptyFlow] **explicitly**. An inherited default made that opt-out
     * invisible: an implementor could omit the member and ship a source that silently never
     * removes anybody, which is precisely what four implementations in this repo did. Written out,
     * the opt-out is a line somebody chose; inherited, it is a line nobody noticed.
     *
     * The emitted key must be the [Tag.peerKey] of the [Tag] **this same source** emitted from
     * [discoveries] for that peer. [discoveryRoster] removes by exact key, so a departure carrying
     * any other identifier — a display name, a socket address, another transport's handle — is
     * indistinguishable from no departure at all, and leaves the same ghost as the explicit
     * [emptyFlow] while looking like it works.
     *
     * A source returning [emptyFlow] here is exactly a source [discoveryRoster]'s **ghost caveat**
     * applies to: everything it discovers stays in the roster forever, because nothing can ever
     * remove it. That caveat covers those sources and no others.
     *
     * A source with a real leave signal stays open until the collector's scope is cancelled; the
     * explicit [emptyFlow] completes at once, having nothing to hold open.
     */
    public fun departures(): Flow<String>
}
