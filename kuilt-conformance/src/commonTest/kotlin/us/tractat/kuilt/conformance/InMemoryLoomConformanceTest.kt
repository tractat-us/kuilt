package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom

/**
 * Verifies the reference [InMemoryLoom] satisfies the shared [SeamConformanceSuite].
 * Keeping this here (rather than in `:kuilt-core`) lets `:kuilt-core` stay free of a
 * test dependency on `:kuilt-conformance`, and exercises the suite from a consumer.
 */
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    override fun newLoom(): Loom = InMemoryLoom()
}
