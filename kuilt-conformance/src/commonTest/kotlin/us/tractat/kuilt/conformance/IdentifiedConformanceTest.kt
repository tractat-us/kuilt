package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.test.fabric.identifiedLoomPair

/** The `identified` 2-peer primitive satisfies the seam contract. */
class IdentifiedConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> = identifiedLoomPair()

    /** `meshDelivery = true` is vacuous here: `identified` is a strictly 2-peer primitive, so there's no third peer to relay to. */
    override fun capabilities() = SeamCapabilities.FULL.copy(securesTransport = false)
    override fun capabilityGaps() = mapOf("securesTransport" to CapabilityGaps.SECURES_TRANSPORT)
}
