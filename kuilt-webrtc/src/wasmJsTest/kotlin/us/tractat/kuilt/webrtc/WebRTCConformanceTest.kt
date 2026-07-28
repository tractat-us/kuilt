package us.tractat.kuilt.webrtc

import us.tractat.kuilt.conformance.CapabilityGaps
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
 */
class WebRTCConformanceTest : SeamConformanceSuite() {

    private val room = "conformance-room"

    override fun newLoomPair(): Pair<Loom, Loom> {
        val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
        val (hostSig, joinerSig) = PairedSignalingChannels.pair()
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
     * `reportsLiveCapability = false`: nothing folds the browser's connectivity signals
     * (`RTCPeerConnection.connectionState`, `navigator.onLine`) into
     * [us.tractat.kuilt.core.Seam.capability], so it sits on the honest
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712/#1544).
     */
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(
            reportsLiveCapability = false,
            // `WebRTCPeerLink.close()` does not collapse `_peers`, so a locally-closed data channel
            // reports its pre-close roster forever. The remote-tear path already collapses correctly,
            // so the fix is to hoist it. Obligation from #1816, tracked in #1853.
            collapsesPeersOnTear = false,
        )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
        "collapsesPeersOnTear" to "https://github.com/tractat-us/kuilt/issues/1853",
    )
}
