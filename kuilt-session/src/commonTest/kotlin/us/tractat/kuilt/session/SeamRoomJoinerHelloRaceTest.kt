package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.HeartbeatConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

/**
 * Regression test for the joiner Hello race:
 *
 * Some transports (MultipeerConnectivity, WebRTC) deliver a [Seam] to the joiner
 * before the underlying transport connection is established. [Seam.peers] starts
 * with only [Seam.selfId] and grows asynchronously as the transport reaches
 * Connected. If [SeamRoom] sends Hello immediately on start, the broadcast lands
 * on an empty peer set and is silently dropped — the admit handshake never completes.
 *
 * The fix: wait for `seam.peers.size > 1` before calling `sendHello()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeamRoomJoinerHelloRaceTest {

    private val selfPeer = PeerId("joiner")
    private val remotePeer = PeerId("host")

    @Test
    fun `joiner Hello is not broadcast before transport peer appears, then fires immediately after`() =
        runTest {
            val broadcastPayloads = mutableListOf<ByteArray>()
            val delayedPeers = MutableStateFlow(setOf(selfPeer))

            val fakeSeam =
                object : Seam {
                    override val selfId: PeerId = selfPeer
                    override val peers: StateFlow<Set<PeerId>> = delayedPeers.asStateFlow()
                    override val incoming: Flow<Swatch> = MutableSharedFlow()

                    override suspend fun broadcast(payload: ByteArray) {
                        broadcastPayloads += payload
                    }

                    override suspend fun sendTo(
                        peer: PeerId,
                        payload: ByteArray,
                    ) = Unit

                    override suspend fun close(reason: CloseReason) = Unit
                }

            val room =
                SeamRoom(
                    seam = fakeSeam,
                    role = SessionRole.Joiner,
                    displayName = "joiner",
                    scope = backgroundScope,
                    clock = { Instant.fromEpochMilliseconds(0L) },
                    heartbeatConfig = HeartbeatConfig(),
                )
            room.start()

            // Give the coroutine scheduler a chance to run the main loop up to the peer-wait.
            advanceTimeBy(50.milliseconds)

            assertAll(
                // No broadcast before the remote peer is visible.
                { assertFalse(broadcastPayloads.any { AdmitMessage.isAdmitFrame(it) }, "Hello must not be sent before transport peer appears") },
            )

            // Remote peer becomes visible — transport reached Connected.
            delayedPeers.value = setOf(selfPeer, remotePeer)

            // Give the coroutine scheduler a chance to unblock the wait and send Hello.
            advanceTimeBy(50.milliseconds)

            val helloPayloads = broadcastPayloads.filter { AdmitMessage.isAdmitFrame(it) }

            assertAll(
                { assertTrue(helloPayloads.isNotEmpty(), "Hello must be sent after transport peer appears") },
                {
                    val msg = AdmitMessage.decode(helloPayloads.first())
                    assertTrue(msg is AdmitMessage.Hello, "Payload must decode as AdmitMessage.Hello, was $msg")
                },
            )

            room.leave()
        }
}
