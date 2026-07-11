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

    /**
     * `meshDelivery = true` here is genuine, not vacuous: [InMemoryLoom] is an
     * N-peer shared mesh (mesh evidence tracked in #1408, Task 1.8).
     */
    override fun capabilities() = SeamCapabilities.FULL.copy(securesTransport = false)
    override fun capabilityGaps() = mapOf("securesTransport" to CapabilityGaps.SECURES_TRANSPORT)
}
