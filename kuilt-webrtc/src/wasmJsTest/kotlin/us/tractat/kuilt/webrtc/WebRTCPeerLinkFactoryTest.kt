package us.tractat.kuilt.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

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
                        hostFactory.host(Pattern(displayName = "host"))
                    }
                val joinerLink =
                    async {
                        joinerFactory.join(ad)
                    }
                val host = hostLink.await()
                val joiner = joinerLink.await()

                host.broadcast("from-host".encodeToByteArray())
                val received = joiner.incoming.first()
                assertContentEquals("from-host".encodeToByteArray(), received.toByteArray())
            }
        }

    /**
     * Guards #454: injecting a seeded [Random] produces a deterministic token prefix
     * on the [PeerId] assigned during [WebRTCPeerLinkFactory.weave].
     *
     * The host's [PeerId] is built from [Pattern.displayName] + '-' + 8 random letters.
     * With a fixed seed the 8-letter suffix is deterministic; the test confirms the
     * peer-id starts with the expected prefix and has the right length.
     */
    @Test
    fun injectedRandomProducesDeterministicPeerIdToken() =
        runTest {
            val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
            val (hostSig, joinerSig) = PairedSignalingChannels.pair()
            val room = "rng-test-room"

            // Build the expected suffix using the same seeded Random.
            val seed = 42
            val expectedSuffix = buildString {
                val r = Random(seed)
                repeat(8) { append(('a' + r.nextInt(26))) }
            }

            val hostFactory =
                WebRTCPeerLinkFactory(
                    signaling = hostSig,
                    room = room,
                    iceConfig = IceConfig.NoServers,
                    facadeFactory = hostFacFactory,
                    random = Random(seed),
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
                    override val displayName = "peer"
                    override val peerKey = room
                }

            coroutineScope {
                val hostLink = async { hostFactory.host(Pattern(displayName = "host")) }
                val joinerLink = async { joinerFactory.join(ad) }
                val host = hostLink.await()
                joinerLink.await()

                // The host's self-id must be "host-<8 letters from seeded RNG>".
                val hostPeerId = host.selfId.value
                assertTrue(
                    hostPeerId.startsWith("host-") && hostPeerId.endsWith(expectedSuffix),
                    "Expected peer-id 'host-$expectedSuffix' but got '$hostPeerId'",
                )
            }
        }
}
