package us.tractat.kuilt.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals

class WebRTCPeerLinkFactoryTest {
    @Test
    fun openAndJoinExchangeFrames() =
        runTest {
            val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
            val (hostSig, joinerSig) = PairedSignalingChannels.pair()
            val room = "test-room"

            val hostFactory =
                WebRTCPeerLinkFactory(
                    signaling = hostSig,
                    room = room,
                    iceConfig = IceConfig.NoServers,
                    facadeFactory = hostFacFactory,
                )
            val joinerFactory =
                WebRTCPeerLinkFactory(
                    signaling = joinerSig,
                    room = room,
                    iceConfig = IceConfig.NoServers,
                    facadeFactory = joinerFacFactory,
                )

            val ad =
                object : Tag {
                    override val displayName = "host-display"
                    override val peerKey = room
                }

            coroutineScope {
                val hostLink =
                    async {
                        hostFactory.open(Pattern(displayName = "host"))
                    }
                val joinerLink =
                    async {
                        joinerFactory.join(ad)
                    }
                val host = hostLink.await()
                val joiner = joinerLink.await()

                host.broadcast("from-host".encodeToByteArray())
                val received = joiner.incoming.first()
                assertContentEquals("from-host".encodeToByteArray(), received.payload)
            }
        }
}
