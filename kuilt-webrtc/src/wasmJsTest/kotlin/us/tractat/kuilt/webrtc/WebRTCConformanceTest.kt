package us.tractat.kuilt.webrtc

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
     * own pinned [room]; [Tag.displayName] is used only for peer-id labelling.
     */
    override fun joinTag(): Tag = object : Tag {
        override val displayName = "host"
        override val peerKey = room
    }
}
