package us.tractat.kuilt.webrtc

import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Tag

/**
 * Verifies that [WebRTCPeerLinkFactory] satisfies every invariant in [SeamConformanceSuite]
 * when backed by the in-memory [PairedFacadeFactory] / [PairedSignalingChannels] harness.
 *
 * Two factories share a fixed room name and are wired via paired fakes so open()/join()
 * can proceed concurrently without a real WebRTC environment. The paired fake already
 * drives both roles — it is the same harness used by [WebRTCPeerLinkFactoryTest].
 *
 * Per ADR-001 §Real-loopback-first: a real RTCPeerConnection loopback is possible only
 * if the wasmJs test runner provides WebRTC. The existing paired-fake harness is the
 * correct CI path and is used here.
 *
 * **The [ICE_CONNECTED] emissions in [newLoomPair] are load-bearing — do not delete them.** The
 * `reportsLiveCapability = true` flag selects the AWAITING branch of
 * [SeamConformanceSuite.wovenSeamCapabilityIsHonest], which blocks on
 * `capability.first { it.availability !is Unknown }`, and the ICE observer is the ONLY thing that
 * can satisfy it — there is no static availability seed ([WebRTCPeerLinkFactory]'s static report
 * supplies the ROLES only). [PairedFacadeFactory] starts from a `null` ICE state, so an unseeded
 * seam publishes `Unknown` forever: the await would never complete and the test would die on
 * `runTest`'s wall-clock backstop. [WebRTCPeerLinkCapabilityTest] pins that unseeded-floor
 * behaviour directly, so the claim this comment rests on is asserted rather than merely
 * asserted-about.
 */
class WebRTCConformanceTest : SeamConformanceSuite() {

    private companion object {
        /**
         * An ICE agent with a working candidate pair — the reading that folds to
         * [us.tractat.kuilt.core.FabricAvailability.Available]. Published on both paired fakes in
         * [newLoomPair] to drive the #1544 observer loop. Required setup, not decoration.
         */
        val ICE_CONNECTED = IceConnectionState.Connected
    }

    private val room = "conformance-room"

    override fun newLoomPair(): Pair<Loom, Loom> {
        val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
        val (hostSig, joinerSig) = PairedSignalingChannels.pair()
        // LOAD-BEARING (#1544/#1712): the ICE observer is the only source of a non-Unknown
        // availability, and the suite's reportsLiveCapability branch AWAITS one. Delete these and
        // the suite hangs to timeout.
        hostFacFactory.emitIceConnectionState(ICE_CONNECTED)
        joinerFacFactory.emitIceConnectionState(ICE_CONNECTED)
        val host = WebRTCPeerLinkFactory(
            signaling = hostSig,
            room = room,
            iceConfig = IceConfig.NoServers,
            facadeFactory = hostFacFactory,
        )
        val joiner = WebRTCPeerLinkFactory(
            signaling = joinerSig,
            room = room,
            iceConfig = IceConfig.NoServers,
            facadeFactory = joinerFacFactory,
        )
        return host to joiner
    }

    /**
     * The joiner's tag. [WebRTCPeerLinkFactory] ignores [Tag.peerKey] and uses its
     * own pinned [room]; [Tag.sessionName] is used only for peer-id labelling.
     */
    override fun joinTag(): Tag = object : Tag {
        override val sessionName = "host"
        override val peerKey = room
    }

    /**
     * RTCDataChannel is DTLS-encrypted; meshDelivery is vacuously true (a strictly
     * 2-peer PeerLink, no third peer to relay to — Task 1.8 / #1408 meshEvidence:
     * 2-peer vacuity).
     *
     * `supportsSendTo = true` (#1409): the resolved remote `PeerId` is reconciled into
     * the roster once the ID-exchange completes, and `sendTo` awaits that resolution,
     * so `sendTo(actualPeerId)` delivers to the named peer.
     *
     * `reportsLiveCapability = true` (#1544): [WebRTCPeerLink] drives its
     * [us.tractat.kuilt.core.Seam.capability] from the peer connection's ICE connection state,
     * whose browser binding is a real `oniceconnectionstatechange` observer in `BrowserRtcFacade` —
     * so this fabric is off the [us.tractat.kuilt.core.FabricAvailability.Unknown] floor. What this
     * harness proves is the seam's *reaction* to a signal; that a real `RTCPeerConnection` actually
     * *emits* one is provable only against real ICE.
     */
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(
            // `WebRTCPeerLink.close()` does not collapse `_peers`, so a locally-closed data channel
            // reports its pre-close roster forever. The remote-tear path already collapses correctly,
            // so the fix is to hoist it. Obligation from #1816, tracked in #1853.
            collapsesPeersOnTear = false,
        )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "collapsesPeersOnTear" to "https://github.com/tractat-us/kuilt/issues/1853",
    )
}
