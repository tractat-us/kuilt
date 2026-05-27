package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom

/**
 * Verifies the reference [InMemoryLoom] satisfies the shared [SeamConformanceSuite].
 * Keeping this here (rather than in `:kuilt-core`) lets `:kuilt-core` stay free of a
 * test dependency on `:kuilt-conformance`, and exercises the suite from a consumer.
 *
 * The same [InMemoryLoom] instance plays both host and joiner — it is designed
 * as a shared in-memory mesh and explicitly supports the self-loopback role.
 */
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    private val loom = InMemoryLoom()
    override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
}
