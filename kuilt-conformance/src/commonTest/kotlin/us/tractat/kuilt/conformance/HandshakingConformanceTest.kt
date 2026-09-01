package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.test.fabric.connectionPair
import us.tractat.kuilt.test.fabric.handshakingLoomPair

/**
 * The `handshaking` 2-peer seam satisfies the seam contract.
 *
 * The pair is woven over a [connectionPair] this harness **keeps both ends of**, so
 * [injectMidSessionDeath] can drop the transport out from under the live session and prove the
 * remote-disconnect half of the `incoming`-completes-on-`Torn` contract (#1442). `handshaking`
 * ends in `identified`, so the seam that dies here is the same `LinkSeam`
 * [IdentifiedConformanceTest] drives — what this harness adds is that a seam which negotiated its
 * identity in-band still tears the same way.
 */
class HandshakingConformanceTest : SeamConformanceSuite() {

    /**
     * The two ends of the in-memory link under the current pair, held so [injectMidSessionDeath]
     * can drop the transport. Tests run one pair at a time, sequentially — the same capture
     * [PeerMeshConformanceTest] makes.
     */
    private var link: Pair<Connection, Connection>? = null

    override fun newLoomPair(): Pair<Loom, Loom> =
        connectionPair().also { link = it }.let(::handshakingLoomPair)

    /** `meshDelivery = true` is vacuous here: `handshaking` is a strictly 2-peer primitive, so there's no third peer to relay to. */
    override fun capabilities() =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)

    override fun capabilityGaps() = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "handshaking()'s Hello preamble: the joiner learns the host's PeerId off the wire before its " +
            "LinkSeam is built. Honest weakness: that exchange is a PRECONDITION of weave() returning, so a " +
            "join path that stopped recording the peer would wedge the weave rather than red this arm.",
        )

    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean =
        dropBothEnds(link, host, joiner)

    /** Proven: this harness drops the transport under a live pair, so no gap. */
    override fun midSessionDeathGap(): String? = null
}
