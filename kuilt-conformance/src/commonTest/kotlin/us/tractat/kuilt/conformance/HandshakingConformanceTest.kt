package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.test.fabric.handshakingLoomPair

/** The `handshaking` 2-peer seam satisfies the seam contract. */
class HandshakingConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> = handshakingLoomPair()

    /** `meshDelivery = true` is vacuous here: `handshaking` is a strictly 2-peer primitive, so there's no third peer to relay to. */
    override fun capabilities() = SeamCapabilities.FULL.copy(securesTransport = false)
    override fun capabilityGaps() = mapOf("securesTransport" to CapabilityGaps.SECURES_TRANSPORT)
}
