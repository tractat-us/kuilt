package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.test.fabric.connectionPair
import us.tractat.kuilt.test.fabric.identifiedLoomPair

/**
 * The `identified` 2-peer primitive satisfies the seam contract.
 *
 * The pair is woven over a [connectionPair] this harness **keeps both ends of**, so
 * [injectMidSessionDeath] can drop the transport out from under the live session and prove the
 * remote-disconnect half of the `incoming`-completes-on-`Torn` contract (#1442).
 */
class IdentifiedConformanceTest : SeamConformanceSuite() {

    /**
     * The two ends of the in-memory link under the current pair, held so [injectMidSessionDeath]
     * can drop the transport. Tests run one pair at a time, sequentially — the same capture
     * [PeerMeshConformanceTest] makes.
     */
    private var link: Pair<Connection, Connection>? = null

    override fun newLoomPair(): Pair<Loom, Loom> =
        connectionPair().also { link = it }.let(::identifiedLoomPair)

    /** `meshDelivery = true` is vacuous here: `identified` is a strictly 2-peer primitive, so there's no third peer to relay to. */
    override fun capabilities() =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)

    override fun capabilityGaps() = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /** #2591: this fixture fills the joiner's roster itself, so the joiner arm cannot fail here. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction(
            "a seeded roster: identifiedLoomPair hands each end the other's PeerId as a constructor argument " +
            "and LinkSeam opens at MutableStateFlow(setOf(selfId, remoteId)), so the joiner's roster is a " +
            "literal before a byte moves. Splitting the fixture would not help - the seam would have to learn " +
            "the id rather than be handed it, which is what the handshaking/peerMesh harnesses cover.",
        )

    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean =
        dropBothEnds(link, host, joiner)

    /** Proven: this harness drops the transport under a live pair, so no gap. */
    override fun midSessionDeathGap(): String? = null
}
