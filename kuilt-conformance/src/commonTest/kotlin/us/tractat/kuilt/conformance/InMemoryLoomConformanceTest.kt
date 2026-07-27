package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam

/**
 * Verifies the reference [InMemoryLoom] satisfies the shared [SeamConformanceSuite].
 * Keeping this here (rather than in `:kuilt-core`) lets `:kuilt-core` stay free of a
 * test dependency on `:kuilt-conformance`, and exercises the suite from a consumer.
 *
 * The same [InMemoryLoom] instance plays both host and joiner — it is designed
 * as a shared in-memory mesh and explicitly supports the self-loopback role.
 *
 * Beyond the usual obligations, this N-peer harness can **inject a membership drain** — its
 * seams share the loom's roster, so closing the joiner removes it from that shared `peers`
 * while the host seam stays [us.tractat.kuilt.core.SeamState.Woven], the drain-without-tear
 * a strictly-2-peer mesh cannot model (#1466). It therefore proves
 * [SeamConformanceSuite.peersDrainWithoutTearOnInjectedMembershipDrain].
 */
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    private val loom = InMemoryLoom()
    override fun newLoomPair(): Pair<Loom, Loom> = loom to loom

    /**
     * `meshDelivery = true` here is genuine, not vacuous: [InMemoryLoom] is an
     * N-peer shared mesh, proven against the shared [MeshConformanceSuite] by
     * [InMemoryLoomMeshConformanceTest] (#1408, Task 1.8).
     */
    override fun capabilities() =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)

    override fun capabilityGaps() = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /**
     * Drain the joiner from the shared roster: closing the joiner seam removes it from the loom's
     * `peers` (which every seam observes) while the host seam is never closed — so the host observes
     * `peers` shrink with its own `state` staying Woven. That is a membership drain, distinct from a
     * transport tear (where both ends would latch Torn).
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        joiner.close()
        return true
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap. */
    override fun membershipDrainGap(): String? = null
}
