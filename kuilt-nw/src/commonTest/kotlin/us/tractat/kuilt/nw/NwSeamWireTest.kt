package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NwSeam] classifies inbound frames by the **type byte**, not by position (#2425 slice 1).
 *
 * Positionally, "the first frame on this connection" was the hello and everything after it was
 * data. Two states were then silently possible rather than refusable — a second hello on a
 * connection that had already resolved (delivered to the consumer as an application frame), and a
 * data frame before any hello (misparsed as a hello, or thrown out of the decoder). Both are now
 * explicit, logged, refused cases, and a third frame type exists that positional classification had
 * nowhere to put: `GOODBYE`.
 *
 * `GOODBYE` is **defined and decoded but sent nowhere** in this slice. It terminates the loser's
 * drain in slice 2; until that lands a received one is a no-op, and this file pins that — a drain
 * arriving early would be a behaviour change nobody asked for yet.
 */
class NwSeamWireTest {

    // ── the type byte is what classifies, and both wrong-position cases are refused ──

    @Test
    fun aHelloOnAnAlreadyResolvedConnectionIsRefusedRatherThanDeliveredAsData() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val subject = rig.weave("c-subject", "peer-subject")
            val control = rig.weave("c-control", "peer-control")

            // A SECOND hello on a connection that already resolved. Positionally this was
            // indistinguishable from data, so the preamble bytes went to the consumer as an
            // application frame; typed, it is a protocol violation and the link is refused.
            rig.api.emitBytesReceived(
                NwBytesReceived(subject, encodeFrame(NwWire.encodeHello(PeerId("peer-subject"), nonce(1)))),
            )
            testScheduler.runCurrent()

            // The receive loop survived: the control link still delivers.
            rig.api.emitBytesReceived(NwBytesReceived(control, encodeFrame(NwWire.encodeData(ALIVE))))
            rig.pumpUntil { rig.received.isNotEmpty() }

            assertAll(
                {
                    assertFalse(
                        PeerId("peer-subject") in rig.seam.peers.value,
                        "a peer that speaks a second hello on a settled link is refused, not tolerated",
                    )
                },
                { assertTrue(PeerId("peer-control") in rig.seam.peers.value, "…and only that link is refused") },
                {
                    assertEquals(
                        listOf(ALIVE.decodeToString()),
                        rig.received.map { it.decodeToString() },
                        "the hello's bytes must NEVER reach the consumer — that is what positional " +
                            "classification did with them",
                    )
                },
                { assertTrue(rig.seam.state.value !is SeamState.Torn, "one bad link is not a torn seam") },
            )
        }

    @Test
    fun aDataFrameBeforeAnyHelloIsRefusedAndRegistersNoPeer() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val control = rig.weave("c-control", "peer-control")

            // Positionally this was the hello slot, so these bytes were fed to `NwHello.decode` —
            // which either threw or, worse, succeeded and registered a phantom peer.
            val early = NwConnectionId("c-early")
            rig.api.emitConnectionOpened(NwConnectionOpened(early, endpoint = null))
            testScheduler.runCurrent()
            rig.api.emitBytesReceived(NwBytesReceived(early, encodeFrame(NwWire.encodeData(TOO_EARLY))))
            testScheduler.runCurrent()

            // REFUSED, not merely ignored — and this is what tells the two apart. A refusal evicts and
            // TOMBSTONES the connection, so a subsequent perfectly-good hello on it must be dropped as
            // well. Downgrade the refusal to a silent drop and the connection stays tracked, this hello
            // resolves, and `peer-late` joins the roster.
            rig.api.emitBytesReceived(NwBytesReceived(early, encodeFrame(NwWire.encodeHello(PeerId("peer-late"), nonce(4)))))
            testScheduler.runCurrent()

            rig.api.emitBytesReceived(NwBytesReceived(control, encodeFrame(NwWire.encodeData(ALIVE))))
            rig.pumpUntil { rig.received.isNotEmpty() }

            assertAll(
                {
                    assertEquals(
                        setOf(rig.self, PeerId("peer-control")),
                        rig.seam.peers.value,
                        "data arriving before an identity registers nobody — no phantom peer, and the " +
                            "refused connection is dead to a later hello too",
                    )
                },
                {
                    assertEquals(
                        listOf(ALIVE.decodeToString()),
                        rig.received.map { it.decodeToString() },
                        "…and the unattributable payload is refused, never delivered with a guessed sender",
                    )
                },
                { assertTrue(rig.seam.state.value !is SeamState.Torn) },
            )
        }

    @Test
    fun aHelloAtAWireVersionThisBuildDoesNotKnowIsRefusedWithoutKillingTheReceiveLoop() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val control = rig.weave("c-control", "peer-control")

            val stranger = NwConnectionId("c-stranger")
            rig.api.emitConnectionOpened(NwConnectionOpened(stranger, endpoint = null))
            testScheduler.runCurrent()
            rig.api.emitBytesReceived(
                NwBytesReceived(stranger, encodeFrame(helloAtVersion(NW_WIRE_VERSION + 7, PeerId("peer-future")))),
            )
            testScheduler.runCurrent()

            rig.api.emitBytesReceived(NwBytesReceived(control, encodeFrame(NwWire.encodeData(ALIVE))))
            rig.pumpUntil { rig.received.isNotEmpty() }

            assertAll(
                {
                    assertFalse(
                        PeerId("peer-future") in rig.seam.peers.value,
                        "a peer speaking an unknown wire version must not be admitted",
                    )
                },
                {
                    assertTrue(
                        PeerId("peer-control") in rig.seam.peers.value,
                        "…and refusing it must not cost the seam its other peers",
                    )
                },
                {
                    assertEquals(
                        listOf(ALIVE.decodeToString()),
                        rig.received.map { it.decodeToString() },
                        "the receive loop survived the refusal — a version break is not a deaf seam",
                    )
                },
            )
        }

    // ── GOODBYE: decoded, and deliberately inert until slice 2 ──────────────────

    @Test
    fun aReceivedGoodbyeIsANoOpInThisSlice() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val conn = rig.weave("c-1", "peer-1")

            rig.api.emitBytesReceived(NwBytesReceived(conn, encodeFrame(NwWire.encodeGoodbye())))
            testScheduler.runCurrent()
            val peersAfterGoodbye = rig.seam.peers.value
            val stateAfterGoodbye = rig.seam.state.value

            // The link is untouched, so it still carries traffic. Slice 2 gives GOODBYE meaning; if
            // draining or a link teardown ever lands here early, this is what reds.
            rig.api.emitBytesReceived(NwBytesReceived(conn, encodeFrame(NwWire.encodeData(ALIVE))))
            rig.pumpUntil { rig.received.isNotEmpty() }

            assertAll(
                { assertEquals(setOf(rig.self, PeerId("peer-1")), peersAfterGoodbye, "GOODBYE evicts nobody yet") },
                { assertEquals(SeamState.Woven, stateAfterGoodbye, "…and moves no state") },
                {
                    assertEquals(
                        listOf(ALIVE.decodeToString()),
                        rig.received.map { it.decodeToString() },
                        "the GOODBYE itself is never delivered to the consumer, and the link keeps working",
                    )
                },
            )
        }

    // ── the payload shapes that must survive the extra byte ──────────────────────

    @Test
    fun aZeroLengthDataFrameIsDeliveredAsAnEmptyPayloadAttributedToItsSender() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val conn = rig.weave("c-1", "peer-1")

            rig.api.emitBytesReceived(NwBytesReceived(conn, encodeFrame(NwWire.encodeData(ByteArray(0)))))
            rig.pumpUntil { rig.received.isNotEmpty() }

            val swatch = rig.received.single()
            assertAll(
                {
                    assertEquals(
                        0,
                        swatch.payloadSize,
                        "an empty payload is a frame — stripping the type byte must leave zero bytes, " +
                            "not fall off the end",
                    )
                },
                { assertEquals(PeerId("peer-1"), swatch.sender) },
            )
        }

    @Test
    fun theSeamPutsATypeByteOnEveryFrameItSends() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // Two real seams over one radio, so the frames asserted on are the ones production
            // wrote — an assertion against a hand-built frame would prove only the codec.
            val radio = FakeNwRadio()
            val apiA = FakeNwApi(radio, deviceId = "dev-a", serviceName = "svc-a")
            val apiB = FakeNwApi(radio, deviceId = "dev-b", serviceName = "svc-b")
            val seamA = NwSeam(PeerId("peer-a"), apiA, seamScope(), Random(0))
            val seamB = NwSeam(PeerId("peer-b"), apiB, seamScope(), Random(1))
            val atB = mutableListOf<Swatch>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { atB += it } }
            testScheduler.runCurrent()

            radio.injectDoubleDial("dev-a", "dev-b")
            assertTrue(
                pumpUntil { seamA.peers.value.size == 2 && seamB.peers.value.size == 2 },
                "the pair must converge before its frames can be read off the ledger",
            )
            val helloTypes = radio.sentFrames.map { it.typeByte }.toSet()

            seamA.broadcast(PAYLOAD)
            assertTrue(pumpUntil { atB.isNotEmpty() }, "the broadcast must cross")

            assertAll(
                {
                    assertEquals(
                        setOf(NwFrameType.Hello.code),
                        helloTypes,
                        "everything sent during formation is a typed HELLO",
                    )
                },
                {
                    assertEquals(
                        NwFrameType.Data.code,
                        radio.sentFrames.last().typeByte,
                        "…and a consumer broadcast is a typed DATA",
                    )
                },
                {
                    assertEquals(
                        PAYLOAD.decodeToString(),
                        atB.single().decodeToString(),
                        "the type byte is stripped before delivery — the consumer sees its own bytes",
                    )
                },
                {
                    assertTrue(
                        radio.sentFrames.none { it.typeByte == NwFrameType.Goodbye.code },
                        "GOODBYE is DEFINED but SENT NOWHERE in this slice — slice 2 gives it meaning",
                    )
                },
            )
        }

    // ---------------------------------------------------------------- harness

    /** One seam over a bare [FakeNwApi], driven by hand-emitted events. */
    private class Rig(
        val self: PeerId,
        val api: FakeNwApi,
        val seam: NwSeam,
        val received: MutableList<Swatch>,
        private val scope: TestScope,
    ) {
        /** Open [connIdValue] and resolve it to [remoteValue] with a well-formed typed hello. */
        suspend fun weave(connIdValue: String, remoteValue: String): NwConnectionId {
            val connId = NwConnectionId(connIdValue)
            val remote = PeerId(remoteValue)
            api.emitConnectionOpened(NwConnectionOpened(connId, endpoint = null))
            scope.testScheduler.runCurrent()
            api.emitBytesReceived(NwBytesReceived(connId, encodeFrame(NwWire.encodeHello(remote, nonce(7)))))
            check(pumpUntil { remote in seam.peers.value }) { "$remote never wove on $connId" }
            return connId
        }

        fun pumpUntil(cond: () -> Boolean): Boolean {
            repeat(MAX_PUMPS) {
                if (cond()) return true
                scope.testScheduler.runCurrent()
            }
            return cond()
        }
    }

    private fun TestScope.rig(): Rig {
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-self")
        val seam = NwSeam(self, api, seamScope(), Random(0))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.incoming.collect { received += it } }
        testScheduler.runCurrent()
        return Rig(self, api, seam, received, this)
    }

    /** A child scope with its OWN Job, so one seam's teardown cannot cancel another's loops. */
    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.pumpUntil(cond: () -> Boolean): Boolean {
        repeat(MAX_PUMPS) {
            if (cond()) return true
            testScheduler.runCurrent()
        }
        return cond()
    }

    private companion object {
        const val MAX_PUMPS = 500
        val ALIVE = "still-alive".encodeToByteArray()
        val TOO_EARLY = "data-before-any-hello".encodeToByteArray()
        val PAYLOAD = "consumer-payload".encodeToByteArray()

        fun nonce(fill: Byte) = ByteArray(NONCE_BYTES) { fill }

        /** The frame's type byte — the first byte AFTER the framing's 4-byte length prefix. */
        val SentFrame.typeByte: Byte get() = bytes[Int.SIZE_BYTES]

        /** A hello declaring [version]; [NwHello.encode] only ever writes [NW_WIRE_VERSION]. */
        fun helloAtVersion(version: Int, id: PeerId): ByteArray {
            val idBytes = id.value.encodeToByteArray()
            return byteArrayOf(NwFrameType.Hello.code, version.toByte()) +
                byteArrayOf(
                    (idBytes.size ushr 24).toByte(),
                    (idBytes.size ushr 16).toByte(),
                    (idBytes.size ushr 8).toByte(),
                    idBytes.size.toByte(),
                ) + idBytes + nonce(3)
        }
    }
}
