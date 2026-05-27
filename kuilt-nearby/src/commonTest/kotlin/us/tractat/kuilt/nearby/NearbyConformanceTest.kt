package us.tractat.kuilt.nearby

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.SeamConformanceSuite

/**
 * Verifies that [NearbyLoom] satisfies every invariant in [SeamConformanceSuite]
 * when backed by the in-memory [FakeNearbyRadio] / [FakeNearbyApi] harness.
 *
 * A fresh [FakeNearbyRadio] is created per test (via [newLoom]) so each test
 * gets isolated, zero-state radio state.
 */
class NearbyConformanceTest : SeamConformanceSuite() {
    override fun newLoom(): Loom = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()))
}
