package us.tractat.kuilt.conformance

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
 */
class CompositeConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> {
        val composite = CompositeLoom(listOf(PlyId("mem") to InMemoryLoom()))
        return composite to composite
    }
}
