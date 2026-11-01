package us.tractat.kuilt.nw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What one failed chunk costs a seam: its link, or if that fails too, the seam. Never a live link that goes
 * on parsing (#3031).
 *
 * The byte stream is ordered and framed, so a chunk that did not reach the framer leaves the next chunk's
 * bytes to be read as a length prefix, and a throw between two frames of a chunk leaves a hole nobody sees.
 * [FailingApi] is a consumer-authored binding, which is the one way in: `NwApi` is public, and its
 * `connectionStates` and `disconnect` run inside the demux.
 *
 * Each arm asserts its rig's firings, since a green here with an unfired rig would mean nothing, and each
 * ends by proving the shared pump still serves another connection or that the seam really is gone.
 */
class NwChunkFailureTest {

    private val self = PeerId("self")

    @Test
    fun aChunkThatFailsBeforeTheFramerCostsItsLinkRatherThanAMisparse() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val conn = NwConnectionId("bytes-first")
            // One frame whose tail is itself a well-formed frame, so reading from the middle yields a real
            // identity rather than a length prefix the framer happens to refuse. Bytes arriving before
            // `connectionOpened` take the first-sight path, which runs `connectionStates` ahead of the framer.
            val inner = encodeFrame(NwWire.encodeHello(PeerId("phantom"), nonce(1)))
            val whole = encodeFrame(NwWire.encodeData(inner))
            val splitAt = whole.size - inner.size
            rig.binding.statesFailures = 1

            rig.chunk(conn, whole.copyOfRange(0, splitAt))
            rig.chunk(conn, whole.copyOfRange(splitAt, whole.size))

            val firings = rig.binding.statesFirings
            val peersAfter = rig.seam.peers.value
            val stateAfter = rig.seam.state.value
            rig.chunk(NwConnectionId("other"), encodeFrame(NwWire.encodeHello(PeerId("bob"), nonce(2))))

            assertAll(
                { assertEquals(1, firings, "the rig must have failed the first chunk") },
                { assertEquals(setOf(self), peersAfter, "the tail was read as a frame of its own") },
                { assertEquals(listOf(conn), rig.binding.disconnected, "the link that lost its place is cut") },
                { assertIs<SeamState.Weaving>(stateAfter, "a failed chunk costs a link, not the seam") },
                { assertEquals(setOf(self, PeerId("bob")), rig.seam.peers.value, "the shared pump still runs") },
            )
        }

    @Test
    fun aThrowBetweenTwoFramesOfAChunkCostsTheLinkRatherThanAHole() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val conn = NwConnectionId("link")
            val alice = PeerId("alice")
            val hello = encodeFrame(NwWire.encodeHello(alice, nonce(1)))
            rig.chunk(conn, hello)
            val wovenFirst = alice in rig.seam.peers.value
            // The second hello refuses the link, whose `disconnect` throws, ahead of the frame behind it.
            rig.binding.disconnectFailures = 1

            rig.chunk(conn, hello + encodeFrame(NwWire.encodeData("in the chunk".encodeToByteArray())))
            rig.chunk(conn, encodeFrame(NwWire.encodeData("the next chunk".encodeToByteArray())))

            val peersAfter = rig.seam.peers.value
            rig.chunk(NwConnectionId("other"), encodeFrame(NwWire.encodeHello(PeerId("bob"), nonce(2))))

            assertAll(
                { assertTrue(wovenFirst, "rig: the link must have been live") },
                { assertEquals(1, rig.binding.disconnectFirings, "the rig must have thrown mid-chunk") },
                { assertEquals(emptyList(), rig.received.map { it.decodeToString() }, "delivered across a hole") },
                { assertEquals(setOf(self), peersAfter, "the peer goes with its link, and the redial heals it") },
                { assertEquals(listOf(conn, conn), rig.binding.disconnected, "tried again, and this one landed") },
                { assertEquals(setOf(self, PeerId("bob")), rig.seam.peers.value, "the shared pump still runs") },
            )
        }

    @Test
    fun whatEscapesTheLinkEvictionTearsTheSeamRatherThanLeavingItHalfApplied() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val conn = NwConnectionId("bytes-first")
            rig.binding.statesFailures = 1
            rig.binding.disconnectFailures = 1

            rig.chunk(conn, encodeFrame(NwWire.encodeHello(PeerId("phantom"), nonce(1))))

            val torn = assertIs<SeamState.Torn>(rig.seam.state.value, "the eviction itself failed")
            assertAll(
                { assertEquals(1, rig.binding.statesFirings, "rig: the chunk failed") },
                { assertEquals(1, rig.binding.disconnectFirings, "rig: and so did the eviction") },
                { assertIs<CloseReason.Error>(torn.reason, "carrying the cause") },
                { assertEquals(0, rig.api.bytesReceivedSubscriberCountForTest(), "the byte stream is let go") },
                { assertTrue(rig.seam.tornDown.isCompleted, "a next session on this binding is not held up") },
            )
        }

    @Test
    fun aSeamBeingCancelledDoesNotEvictOnItsWayOut() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            rig.binding.statesFailures = 1
            rig.binding.onStatesFailure = { rig.scopeJob.cancel() }

            rig.chunk(NwConnectionId("bytes-first"), encodeFrame(NwWire.encodeHello(PeerId("phantom"), nonce(1))))

            assertAll(
                { assertEquals(1, rig.binding.statesFirings, "rig: the failure landed during the cancellation") },
                { assertEquals(emptyList(), rig.binding.disconnected, "a cancel is not this link's fault") },
            )
        }

    private class Rig(
        val api: FakeNwApi,
        val binding: FailingApi,
        val seam: NwSeam,
        val scopeJob: Job,
        val received: MutableList<Swatch>,
        private val scope: TestScope,
    ) {
        /** One chunk from the binding, on a connection this seam may never have heard of. */
        suspend fun chunk(conn: NwConnectionId, bytes: ByteArray) {
            api.emitBytesReceived(NwBytesReceived(conn, bytes))
            repeat(PUMPS) { scope.testScheduler.runCurrent() }
        }
    }

    private fun TestScope.rig(): Rig {
        val api = FakeNwApi(FakeNwRadio(), deviceId = "self-device", serviceName = "self-device")
        val binding = FailingApi(api)
        val scopeJob = Job(backgroundScope.coroutineContext[Job])
        val seam = NwSeam(self, binding, CoroutineScope(backgroundScope.coroutineContext + scopeJob), Random(0))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.incoming.collect { received += it } }
        testScheduler.runCurrent()
        return Rig(api, binding, seam, scopeJob, received, this)
    }

    /**
     * A binding whose two calls inside the demux fail on demand: [connectionStates] with a plain exception, and
     * [disconnect] with a cancellation it minted itself, the one failure `runCatchingCancellable` lets through.
     */
    private class FailingApi(private val api: FakeNwApi) : NwApi by api {
        var statesFailures = 0
        var statesFirings = 0
        var onStatesFailure: () -> Unit = {}
        var disconnectFailures = 0
        var disconnectFirings = 0
        val disconnected = mutableListOf<NwConnectionId>()

        override val connectionStates: StateFlow<Map<NwConnectionId, NwConnState>>
            get() {
                if (statesFailures == 0) return api.connectionStates
                statesFailures -= 1
                statesFirings += 1
                onStatesFailure()
                throw IllegalStateException("the binding's state read failed")
            }

        override suspend fun disconnect(connectionId: NwConnectionId) {
            disconnected += connectionId
            if (disconnectFailures > 0) {
                disconnectFailures -= 1
                disconnectFirings += 1
                throw CancellationException("minted by the binding, not this seam's own")
            }
            api.disconnect(connectionId)
        }
    }

    private companion object {
        const val PUMPS = 40

        fun nonce(fill: Byte) = ByteArray(NONCE_BYTES) { fill }
    }
}
