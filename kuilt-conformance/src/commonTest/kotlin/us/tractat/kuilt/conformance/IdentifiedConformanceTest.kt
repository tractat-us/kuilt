package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.test.fabric.identifiedLoomPair

/** The `identified` 2-peer primitive satisfies the seam contract. */
class IdentifiedConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> = identifiedLoomPair()
}
