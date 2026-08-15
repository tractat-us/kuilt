package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/** The peer every reference source below advertises and then withdraws. */
internal const val REFERENCE_PEER_KEY: String = "peer-1"

/** The session name that peer broadcasts — deliberately *not* its [Tag.peerKey]. */
internal const val REFERENCE_SESSION_NAME: String = "Alice's game"

/**
 * The reference [PeerDiscoverySource]: two independent feeds, a departure keyed exactly as the
 * arrival was.
 *
 * Independent is the load-bearing word. [departures] is not derived from, gated on, or fed by
 * anything [discoveries] opens, so it emits to a lone collector — which is what
 * `DiscoverySourceConformanceSuite.departuresEmitsWithNoConcurrentDiscoveriesCollector` asks for
 * and what a session-scoped departure feed cannot do.
 */
internal class ReferenceDiscoverySource : PeerDiscoverySource {
    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    private val arrivals = MutableSharedFlow<Tag>(extraBufferCapacity = 8)
    private val leaves = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override fun discoveries(): Flow<Tag> = arrivals

    override fun departures(): Flow<String> = leaves

    suspend fun advertise() {
        arrivals.emit(InMemoryTag(sessionName = REFERENCE_SESSION_NAME, peerKey = REFERENCE_PEER_KEY))
    }

    suspend fun withdraw() {
        leaves.emit(REFERENCE_PEER_KEY)
    }
}

/**
 * A fixed-roster source with no leave signal at all — the honest
 * [DepartureFixture.NoLeaveSignal] shape, and the one every silent `emptyFlow()` in this repo
 * *claims* to be.
 */
internal class NoLeaveSignalDiscoverySource : PeerDiscoverySource {
    override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    private val arrivals = MutableSharedFlow<Tag>(extraBufferCapacity = 8)

    override fun discoveries(): Flow<Tag> = arrivals

    // No leave signal: this source only ever learns that a peer appeared.
    override fun departures(): Flow<String> = emptyFlow()

    suspend fun advertise() {
        arrivals.emit(InMemoryTag(sessionName = REFERENCE_SESSION_NAME, peerKey = REFERENCE_PEER_KEY))
    }
}
