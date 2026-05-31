package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.composite.CompositeLoom

/**
 * A single-ply [CompositeLoom] over a shared in-memory mesh must satisfy every
 * seam-contract invariant — composing does not weaken the contract.
 *
 * Both host and joiner use the same [CompositeLoom] instance backed by one
 * [InMemoryLoom] ply, matching the in-process radio fabric pattern where the
 * same factory serves all peers.
 *
 * [UnconfinedTestDispatcher] is injected so the composite's internal reconciliation
 * coroutines (Announce round-trip, peer rollup) run eagerly. This lets the
 * conformance suite's synchronous [peers.value] assertions see the completed state
 * without waiting. Production code uses the confined single-thread default, which
 * is race-free but lazy relative to the test's `.value` reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> {
        val composite = CompositeLoom(
            plies = listOf(PlyId("mem") to InMemoryLoom()),
            dispatcher = UnconfinedTestDispatcher(),
        )
        return composite to composite
    }
}
