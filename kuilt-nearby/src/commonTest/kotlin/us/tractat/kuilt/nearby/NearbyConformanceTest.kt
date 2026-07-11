package us.tractat.kuilt.nearby

import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom

/**
 * Verifies that [NearbyLoom] satisfies every invariant in [SeamConformanceSuite]
 * when backed by the in-memory [FakeNearbyRadio] / [FakeNearbyApi] harness.
 *
 * A fresh [NearbyLoom] (sharing one [FakeNearbyApi] / [FakeNearbyRadio]) is created
 * per test via [newLoomPair], so each test gets isolated, zero-state radio state.
 * The same instance plays both host and joiner — [FakeNearbyRadio] is explicitly
 * designed as a single fake that handles both roles for one [NearbyLoom].
 */
class NearbyConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> =
        NearbyLoom(FakeNearbyApi(FakeNearbyRadio())).let { it to it }

    /**
     * All capabilities honoured — Nearby Connections encrypts every connection
     * unconditionally; meshDelivery vacuously true (NearbyLoom currently weaves one
     * 2-peer link per session — Task 1.8 / #1408 meshEvidence: 2-peer vacuity).
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
    override fun capabilityGaps(): Map<String, String> = emptyMap()
}
