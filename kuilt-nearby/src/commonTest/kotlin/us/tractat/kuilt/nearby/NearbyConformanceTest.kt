package us.tractat.kuilt.nearby

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
}
