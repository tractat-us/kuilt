package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A [CompositeSeam]'s per-ply **inbound** pump must survive anything a peer can put on the wire (#1788).
 *
 * ### The hazard this pins
 * Every other pump in `attachPly` collects a *local* flow. This one collects frames a **remote peer**
 * chose, so "what if this throws" is not a question about consumer code: `PlyFrame.decode` is fed
 * peer-supplied bytes, and before #1788 a **2-byte** frame index-faulted inside `readInt` before the
 * `require` written to reject it. The throw escaped `.onEach { onPlyFrame(id, swatch) }.launchIn(plyScope)`
 * and that ply was deaf for the life of the seam — while staying `Woven`, so the composite kept
 * advertising it as a send target and nothing observable said otherwise. Any peer that can send bytes
 * could permanently deafen one ply of somebody else's composite.
 *
 * On Kotlin/Native it is worse than deafness: `plyScope`'s job is a
 * [kotlinx.coroutines.SupervisorJob], suppressing *parent* propagation is exactly what routes the throw
 * to the global handler, and with no `setUnhandledExceptionHook` installed the runtime **aborts the
 * process**. That dimension cannot be pinned from `runTest` — kotlinx-coroutines-test collects the throw
 * and reports it as a test failure, so a `runTest` body passes whether the crash is fixed or not — and is
 * pinned separately by `CompositeMalformedFrameProcessSurvivalTest` (a bare `@Test` on `macosArm64Test`).
 *
 * These tests own the other half, deterministically and on every target: the frame is **dropped and
 * reported**, and the pump keeps delivering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeInboundPumpTest {

    @Test
    fun aTwoByteFrameIsDroppedAndReportedAndThePumpKeepsDelivering() = runTest {
        val ply = FakeSeam(selfId = PeerId("ply-$PLY_NAME"))
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = listOf(PLY to OneSeamLoom(ply) as Loom),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { composite.incoming.collect { received += it } }
        runCurrent()

        // THE crash frame: a valid Data tag plus one byte, so `readInt(bytes, 1)` reaches for bytes[1..4].
        ply.deliver(REMOTE_TRANSPORT, PlyFrame.encode(PlyFrame.Data(REMOTE_COMPOSITE, 0L, byteArrayOf())).copyOf(2))
        runCurrent()
        // A good frame AFTER it. This is the assertion that matters: pre-fix the pump is already dead here
        // and this payload never arrives, no matter how long the test waits.
        ply.deliver(REMOTE_TRANSPORT, PlyFrame.encode(PlyFrame.Data(REMOTE_COMPOSITE, 1L, GOOD.encodeToByteArray())))
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(GOOD),
                    received.map { it.decodeToString() },
                    "the malformed frame killed this ply's inbound pump — it is deaf for the life of the seam",
                )
            },
            // Absorbed is not silent: kuilt-core is logger-free, so the drop must reach the consumer
            // carrying the ply's identity, the phase, and the decoder's own exception.
            {
                assertEquals(
                    listOf(PLY to PlyReconcileException.Phase.INBOUND),
                    raised.map { it.plyId to it.phase },
                    "a dropped frame must be reported through onPlyFailure, not swallowed",
                )
            },
            { assertIs<IllegalArgumentException>(raised.single().cause, "the cause must be the decoder's rejection") },
            // …and the ply is neither torn nor detached. Tearing would hand any peer a one-frame way to
            // remove a ply from someone else's composite.
            { assertIs<SeamState.Woven>(ply.state.value, "a malformed frame must not tear the ply's transport") },
            { assertEquals(setOf(PLY), composite.plies.value.keys, "…nor detach it from the composite") },
            { assertIs<SeamState.Woven>(composite.state.value, "…nor degrade the composite") },
        )

        composite.close(CloseReason.Normal)
    }

    @Test
    fun everyMalformedShapeAPeerCanSendIsDroppedRatherThanFatal() = runTest {
        val ply = FakeSeam(selfId = PeerId("ply-$PLY_NAME"))
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = listOf(PLY to OneSeamLoom(ply) as Loom),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { composite.incoming.collect { received += it } }
        runCurrent()

        // The empty frame, an unknown tag, a truncated header, a NEGATIVE declared id length, and one that
        // OVERFLOWS the offset arithmetic — the last two defeated the old additive `require` outright.
        listOf(
            byteArrayOf(),
            byteArrayOf(99),
            PlyFrame.encode(PlyFrame.Announce(REMOTE_COMPOSITE)).copyOf(3),
            headerDeclaring(DATA_TAG, -1),
            headerDeclaring(DATA_TAG, Int.MAX_VALUE),
            headerDeclaring(ANNOUNCE_TAG, Int.MAX_VALUE),
        ).forEach { malformed ->
            ply.deliver(REMOTE_TRANSPORT, malformed)
            runCurrent()
        }
        ply.deliver(REMOTE_TRANSPORT, PlyFrame.encode(PlyFrame.Data(REMOTE_COMPOSITE, 0L, GOOD.encodeToByteArray())))
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(GOOD),
                    received.map { it.decodeToString() },
                    "the pump must survive every malformed shape and still deliver the frame after them",
                )
            },
            { assertEquals(MALFORMED_SHAPES, raised.size, "each dropped frame is reported exactly once") },
            {
                assertTrue(
                    raised.all { it.phase == PlyReconcileException.Phase.INBOUND && it.plyId == PLY },
                    "every report must name this ply and the inbound phase",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    /** A syntactically well-formed 5-byte header for [tag] declaring [declaredIdLength], plus a short body. */
    private fun headerDeclaring(tag: Byte, declaredIdLength: Int): ByteArray =
        ByteArray(5 + 16).also { frame ->
            frame[0] = tag
            frame[1] = (declaredIdLength ushr 24).toByte()
            frame[2] = (declaredIdLength ushr 16).toByte()
            frame[3] = (declaredIdLength ushr 8).toByte()
            frame[4] = declaredIdLength.toByte()
        }

    /** A [Loom] weaving one given ply seam. */
    private class OneSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    private companion object {
        const val PLY_NAME = "only"
        const val GOOD = "after-the-bad-frame"
        const val MALFORMED_SHAPES = 6
        val PLY = PlyId(PLY_NAME)
        val REMOTE_TRANSPORT = PeerId("remote-transport")
        val REMOTE_COMPOSITE = PeerId("remote-composite")

        /** Read off the encoder rather than duplicated, so a tag renumbering cannot silently pass. */
        val ANNOUNCE_TAG: Byte = PlyFrame.encode(PlyFrame.Announce(PeerId("t")))[0]
        val DATA_TAG: Byte = PlyFrame.encode(PlyFrame.Data(PeerId("t"), 0L, byteArrayOf()))[0]
    }
}
