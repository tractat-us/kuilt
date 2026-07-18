package us.tractat.kuilt.webrtc

import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [WebRTCPeerLinkFactory] self-reports the WebRTC data-channel fabric's roles:
 * it establishes a WebRTC link and carries data over it.
 */
class WebRTCLoomCapabilityTest {
    @Test
    fun declaresWebRtcAndDataRoles() {
        val (facadeFactory, _) = PairedFacadeFactory.pair()
        val (signaling, _) = PairedSignalingChannels.pair()
        val loom = WebRTCPeerLinkFactory(
            signaling = signaling,
            room = "test-room",
            iceConfig = IceConfig.NoServers,
            facadeFactory = facadeFactory,
        )
        assertEquals(setOf(TransportRole.WebRtc, TransportRole.Data), loom.capability().roles)
    }
}
