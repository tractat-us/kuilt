package us.tractat.kuilt.multipeer

import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag

/**
 * Verifies that [MultipeerPeerLinkFactory] satisfies every invariant in
 * [SeamConformanceSuite] when backed by a [DeliveringFakeMultipeerNativeLib].
 *
 * ## Why a delivering fake at the JNA boundary?
 * MultipeerConnectivity is architecturally role-split: one factory hosts (advertises +
 * auto-accepts), another joins (browses + connects). One instance cannot play both roles —
 * `open()` and `join()` both `check(activeSession == null)` on the same factory. There is
 * also no in-process real transport (the Apple radio is macOS/device-only with no loopback
 * mode). Per ADR-001 §"Per-fabric harness implications" the CI path routes through a
 * delivering fake at the JNA boundary.
 *
 * The [DeliveringFakeMultipeerNativeLib] (the "bus") is shared between the two factories.
 * It routes `mc_session_broadcast` from the host's session into the joiner's data callback
 * and vice-versa, and fires peer-state connected events once both [BridgePeerLink]
 * constructors have registered their callbacks — completing the virtual MC handshake.
 *
 * ## joinTag override
 * [SeamConformanceSuite.joinTag] must return a [MultipeerAdvertisement] so that
 * `MultipeerPeerLinkFactory.weave(Rendezvous.Existing(...))` does not throw. The handle
 * value is arbitrary — the delivering fake ignores it and always routes to [JOINER_SESSION].
 */
class MultipeerConformanceTest : SeamConformanceSuite() {

    companion object {
        private const val HOST_DISPLAY_NAME = "conformance-host"
        private const val JOINER_DISPLAY_NAME = "conformance-joiner"
        private const val FAKE_SERVICE_TYPE = "kuilt-test"
        private const val FAKE_HOST_HANDLE = "fake-host-handle"

        /** Both sides of the 2-peer link — what a genuine transport death must sever. */
        private const val BOTH_ENDS = 2
    }

    // The delivering fake backing the current pair, captured so injectSelfDial can fire a
    // self-peer event on the host session. Tests run one pair at a time, sequentially.
    private var bus: DeliveringFakeMultipeerNativeLib? = null

    override fun newLoomPair(): Pair<Loom, Loom> {
        val bus = DeliveringFakeMultipeerNativeLib(
            hostPeerId = HOST_DISPLAY_NAME,
            joinerPeerId = JOINER_DISPLAY_NAME,
        )
        this.bus = bus
        val hostFactory = MultipeerPeerLinkFactory(
            displayName = HOST_DISPLAY_NAME,
            serviceType = FAKE_SERVICE_TYPE,
            injectedLib = bus,
            injectedRuntimeHandle = DeliveringFakeMultipeerNativeLib.HOST_SESSION,
        )
        val joinerFactory = MultipeerPeerLinkFactory(
            displayName = JOINER_DISPLAY_NAME,
            serviceType = FAKE_SERVICE_TYPE,
            injectedLib = bus,
            injectedRuntimeHandle = DeliveringFakeMultipeerNativeLib.JOINER_SESSION,
        )
        return hostFactory to joinerFactory
    }

    /**
     * The joiner must present a [MultipeerAdvertisement] so [MultipeerPeerLinkFactory.weave]
     * can call `mc_runtime_join`. The handle is arbitrary — the delivering fake routes all
     * join calls to [DeliveringFakeMultipeerNativeLib.JOINER_SESSION] regardless.
     */
    override fun joinTag(): Tag = MultipeerAdvertisement(
        handle = FAKE_HOST_HANDLE,
        sessionName = HOST_DISPLAY_NAME,
        serviceType = FAKE_SERVICE_TYPE,
    )

    /**
     * Drive the host session to see a peer-state event for its OWN peerId — the #1466 self-dial.
     * Real MultipeerConnectivity hands a device that both advertises and browses its own
     * `MCPeerID`; [us.tractat.kuilt.multipeer.internal.BridgePeerLink]'s self-connection guard
     * (`peer == selfId`) must drop it, proving [SeamConformanceSuite.selfDialIsRejected] on a
     * live, already-woven seam.
     */
    override suspend fun injectSelfDial(host: Seam): Boolean =
        bus?.injectHostSelfDial() ?: false

    /** Proven: this harness fires a genuine self-peer event through the fake, so no gap. */
    override fun selfDialDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * Kill the transport under both ends with no `close()` anywhere: the delivering fake tells each
     * `BridgePeerLink` its remote went `isConnected = 0`, which is how a real `MCSession` dies when
     * the radio drops rather than when the application asks. Both links then reach their
     * last-peer teardown, latch [us.tractat.kuilt.core.SeamState.Torn] and complete `incoming` — the
     * twin of the `FakeMCSessionBus.dropTransport()` that already proves the Apple side (#1442).
     *
     * ## Why this returns a count rather than a bare `true`
     *
     * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] reads only *terminal* state,
     * so it would pass just as happily on a pair that was already dead — crediting a tear this rig
     * did not cause. Two guards make the injection prove it is the thing being observed: the `check`
     * asserts both seams were live *before* the drop (the `dropBothEnds` pattern in
     * `:kuilt-conformance`), and the fake reports how many links it **actually** severed (the
     * `FakeNwRadio.dropAllLinks` pattern). Anything but both ends leaves this honestly `false`, so
     * the harness reads as unproven and [midSessionDeathDeclaration] reds — never falsely green.
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        check(host.state.value !is SeamState.Torn && joiner.state.value !is SeamState.Torn) {
            "mid-session-death rig precondition: both seams must be live before the transport is " +
                "dropped, or the obligation would pass on a tear this rig did not cause; got " +
                "host=${host.state.value}, joiner=${joiner.state.value}"
        }
        return bus?.dropTransport() == BOTH_ENDS
    }

    /** Proven: this harness kills the transport under a live session, so no gap (#1442). */
    override fun midSessionDeathDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * Every capability honoured but one — MultipeerConnectivity requires encryption
     * (MCEncryptionRequired) and is an N≤8 peer mesh. meshEvidence (Task 1.8 / #1408):
     * MC mesh; a MeshConformanceSuite subclass is deprioritised (module slated for
     * retirement by kuilt-nw, #1403).
     *
     * `reportsLiveCapability = false`: no `MCSession`/path observer feeds
     * [us.tractat.kuilt.core.Seam.capability], so it sits on the honest
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712/#1542).
     */
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(
            reportsLiveCapability = false,
        )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "BridgePeerLink's roster opens at { selfId } and grows only in its peer-state callback, so a " +
            "joiner that mishandled that callback reds. Honest weakness: the shared fake native lib fires " +
            "BOTH ends' callbacks once both links have registered, so this proves the joiner PROCESSES its " +
            "callback, not that a negotiation across two devices happened.",
        )
}
