package us.tractat.kuilt.nearby

import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState

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
 * ([us.tractat.kuilt.core.freshPeerId]), so there is no stable per-device identity that a self-dial
 * could echo back: `remote == selfId` (the #1466 condition) cannot arise in the current impl. There is
 * therefore no live self-connection a harness could inject into a host seam and no seam-level guard
 * to prove — so this harness is honestly *tracked* under #1502 rather than overriding the hook. (A
 * future symmetric advertise+browse rework of [NearbyLoom] would revisit this.)
 *
 * **The [BOTH_RADIOS_ON] emission in [newLoomPair] is load-bearing — do not delete it.** The
 * `reportsLiveCapability = true` flag selects the AWAITING branch of
 * [SeamConformanceSuite.wovenSeamCapabilityIsHonest], which blocks on
 * `capability.first { it.availability !is Unknown }`; and since #1712 the radio observer is the ONLY
 * thing that can satisfy it — there is no static availability seed left ([NearbyLoom]'s static
 * report supplies the ROLES only). [FakeNearbyApi] starts from a `null` radio state, so an unseeded
 * [NearbySeam] publishes `Unknown` forever: the await would never complete, the test would die on
 * `runTest`'s wall-clock backstop, and it would do so on every common target (jvm, Android,
 * iosSimulatorArm64, macosArm64). [NearbySeamCapabilityTest] pins that unseeded-floor behaviour
 * directly, so the claim this comment rests on is asserted rather than merely asserted-about.
 */
class NearbyConformanceTest : SeamConformanceSuite() {

    private companion object {
        /**
         * Both Nearby radios powered. Published on the fake in [newLoomPair] to drive the seams'
         * #1543 radio-observer loop, which since #1712 is the ONLY source of a
         * non-[us.tractat.kuilt.core.FabricAvailability.Unknown] availability — [NearbySeam] no
         * longer seeds one from [FakeNearbyApi.availability] (that answers *platform support*, not
         * *live radios*), so the static report supplies roles only. Required setup, not decoration.
         */
        val BOTH_RADIOS_ON = NearbyRadioState(
            bluetooth = NearbyRadioStatus.On,
            wifi = NearbyRadioStatus.On,
        )

        /** Both sides of the 2-peer link — what a genuine transport death must sever. */
        const val BOTH_ENDPOINTS = 2
    }

    /** The fake backing the current pair, held so [injectMidSessionDeath] can drop the transport. */
    private var api: FakeNearbyApi? = null

    override fun newLoomPair(): Pair<Loom, Loom> {
        val api = FakeNearbyApi(FakeNearbyRadio())
        this.api = api
        // LOAD-BEARING (#1543/#1712): the radio observer is the only source of a non-Unknown
        // availability, and the suite's reportsLiveCapability branch AWAITS one. Delete this and the
        // suite hangs to timeout.
        api.emitRadioState(BOTH_RADIOS_ON)
        return NearbyLoom(api).let { it to it }
    }

    /**
     * Every capability honoured — Nearby Connections encrypts every connection unconditionally;
     * meshDelivery vacuously true (NearbyLoom currently weaves one 2-peer link per session —
     * Task 1.8 / #1408 meshEvidence: 2-peer vacuity).
     *
     * `reportsLiveCapability = true` (#1543): [NearbySeam] drives its
     * [us.tractat.kuilt.core.Seam.capability] from [NearbyApi.radioState], whose Android binding is
     * a real Bluetooth/Wi-Fi state observer in `GmsNearbyApi` — so this fabric is off the
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor. What this harness proves is the
     * seam's *reaction* to a signal; that the Android runtime actually *emits* one is provable only
     * on a device.
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL

    override fun capabilityGaps(): Map<String, String> = emptyMap()

    /**
     * Kill the transport under both seams with no `close()` anywhere: the fake radio fires
     * [EndpointDisconnected] for each side's own endpoint, which is how a Nearby connection dies
     * when the radio drops rather than when the application asks. Each seam loses its last
     * endpoint, latches [SeamState.Torn] and completes `incoming` — the shape
     * [NearbySeamTearDownTest.lastEndpointDisconnectLatchesTornAndCompletesIncoming] pins at unit
     * level, driven here through the whole loom (#1442).
     *
     * ## Why this returns a count rather than a bare `true`
     *
     * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] reads only *terminal* state,
     * so it would pass just as happily on a pair that was already dead — crediting a tear this rig
     * did not cause. Two guards make the injection prove it is the thing being observed: the `check`
     * asserts both seams were live *before* the drop (the `dropBothEnds` pattern in
     * `:kuilt-conformance`), and the fake reports how many endpoints it **actually** severed (the
     * `FakeNwRadio.dropAllLinks` pattern). Anything but both leaves this honestly `false`, so the
     * harness reads as unproven and [midSessionDeathDeclaration] reds — never falsely green.
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        check(host.state.value !is SeamState.Torn && joiner.state.value !is SeamState.Torn) {
            "mid-session-death rig precondition: both seams must be live before the transport is " +
                "dropped, or the obligation would pass on a tear this rig did not cause; got " +
                "host=${host.state.value}, joiner=${joiner.state.value}"
        }
        return api?.dropTransport() == BOTH_ENDPOINTS
    }

    /** Proven: this harness disconnects both endpoints under a live session, so no gap (#1442). */
    override fun midSessionDeathDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "NearbySeam.admitRemote, from the handshake the joiner itself completed. Since #1878 the roster " +
            "belongs to the WEAVE, not the loom: each weave mints its own flow seeded with its own id, so " +
            "this loom-to-loom harness owns two independent rosters and the joiner arm genuinely reds. That " +
            "is the whole of #2591 - the joiner reported { selfId } forever on two real devices while the " +
            "pre-#1878 loom-wide flow covered for it here.",
        )
}
