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
import us.tractat.kuilt.core.SeamState
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
 * before the underlying connection is established. [SeamRoom] must not send Hello
 * until [SeamState.Woven] — doing so earlier risks a silent drop on an empty peer
 * set, leaving the admit handshake permanently stuck.
 *
 * The fix: await `seam.state.first { it is SeamState.Woven }` before `sendHello()`.
 * This test drives the fake via [state], keeping [peers] at `{self}` throughout, so
 * the only gate that can unblock the Hello is the fabric lifecycle transition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeamRoomJoinerHelloRaceTest {

    private val selfPeer = PeerId("joiner")

    @Test
    fun joinerHelloWaitsForWovenThenFiresImmediately() =
        runTest {
            val broadcastPayloads = mutableListOf<ByteArray>()
            // peers stays {self} throughout — only state drives the gate.
            val delayedState = MutableStateFlow<SeamState>(SeamState.Weaving)

            val fakeSeam =
                object : Seam {
                    override val selfId: PeerId = selfPeer
                    override val peers: StateFlow<Set<PeerId>> =
                        MutableStateFlow(setOf(selfPeer)).asStateFlow()
                    override val state: StateFlow<SeamState> = delayedState.asStateFlow()
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

            // Give the coroutine scheduler a chance to run the main loop up to the state-wait.
            advanceTimeBy(50.milliseconds)

            assertAll(
                // No broadcast while state is still Weaving.
                { assertFalse(broadcastPayloads.any { AdmitMessage.isAdmitFrame(it) }, "Hello must not be sent while SeamState.Weaving") },
            )

            // Fabric transitions to Woven — transport reached Connected.
            delayedState.value = SeamState.Woven

            // Give the coroutine scheduler a chance to unblock the wait and send Hello.
            advanceTimeBy(50.milliseconds)

            val helloPayloads = broadcastPayloads.filter { AdmitMessage.isAdmitFrame(it) }

            assertAll(
                { assertTrue(helloPayloads.isNotEmpty(), "Hello must be sent after SeamState.Woven") },
                {
                    val msg = AdmitMessage.decode(helloPayloads.first())
                    assertTrue(msg is AdmitMessage.Hello, "Payload must decode as AdmitMessage.Hello, was $msg")
                },
            )

            room.leave()
        }
}
