package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.test.fabric.handshakingLoomPair

/** The `handshaking` 2-peer seam satisfies the seam contract. */
class HandshakingConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> = handshakingLoomPair()
}
