package us.tractat.kuilt.nearby

import us.tractat.kuilt.conformance.CapabilityGaps
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
 *
 * ## Why `injectSelfDial` stays at the default `false` (tracked by `CapabilityGaps.SELF_DIAL`)
 * Unlike a genuine mesh fabric (`NwSeam`, Multipeer's `BridgePeerLink`), kuilt's [NearbyLoom]
 * is **role-split**: `open()` only advertises and `join()` only discovers — a single seam never
 * both advertises AND browses. And every weave mints a fresh random [us.tractat.kuilt.core.PeerId]
 * ([NearbyLoom.freshPeerId]), so there is no stable per-device identity that a self-dial could
 * echo back: `remote == selfId` (the #1466 condition) cannot arise in the current impl. There is
 * therefore no live self-connection a harness could inject into a host seam and no seam-level guard
 * to prove — so this harness is honestly *tracked* under #1502 rather than overriding the hook. (A
 * future symmetric advertise+browse rework of [NearbyLoom] would revisit this.)
 */
class NearbyConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> =
        NearbyLoom(FakeNearbyApi(FakeNearbyRadio())).let { it to it }

    /**
     * Every capability honoured but one — Nearby Connections encrypts every connection
     * unconditionally; meshDelivery vacuously true (NearbyLoom currently weaves one
     * 2-peer link per session — Task 1.8 / #1408 meshEvidence: 2-peer vacuity).
     *
     * `reportsLiveCapability = false`: nothing feeds a live path signal into
     * [us.tractat.kuilt.core.Seam.capability], so it sits on the honest
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712/#1543).
     */
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(
            reportsLiveCapability = false,
        )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )
}
