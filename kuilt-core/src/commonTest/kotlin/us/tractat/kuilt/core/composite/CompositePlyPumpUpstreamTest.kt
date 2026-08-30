package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A ply `Seam` whose own `state`/`capability`/`peers` **flow** fails must not take the composite with it
 * (#1788 item 2).
 *
 * ### The half a body guard structurally cannot see
 * `attachPly`'s five mirror/announce pumps collect flows a **consumer** wrote. A throw *in* the collector
 * body is one thing; a throw *by the flow* is another — it terminates the flow, so it never enters an
 * `onEach` body's `try` at all. It then escapes `collect`, escapes the `launch`, and on Kotlin/Native
 * reaches the global handler and **aborts the process**, exactly as a 2-byte frame did before #1809. That
 * is why the guard is `pumpIn`, which owns both halves, rather than a sixth hand-rolled body guard.
 *
 * Lower reachability than #1809's — this needs a misbehaving local `Seam`, not peer bytes — and identical
 * fatality.
 *
 * ### What these tests can and cannot prove
 * They pin the *deterministic* half on every target: the failure is reported with the ply's identity and
 * the phase that says the pump is over, the composite keeps working, and **nothing reaches the scope's
 * [CoroutineExceptionHandler]** — the handler standing where the runtime's global handler stands on a
 * device, which is the abort route asserted one step before the abort. They cannot pin the abort itself:
 * `runTest` collects an unhandled throw and reports it as a test failure, so every pump test written
 * under it is structurally blind to the real fatality. `PumpInProcessSurvivalTest` (a bare `@Test` in
 * `appleTest`) owns that dimension for the helper all six sites now run through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositePlyPumpUpstreamTest {

    @Test
    fun aPlyWhosePeersFlowFailsIsReportedAndTheCompositeKeepsWorking() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val unhandled = mutableListOf<Throwable>()
        val raised = mutableListOf<PlyReconcileException>()
        val ply = FailingPeersSeam(FakeSeam(selfId = PeerId("ply-$PLY_NAME")))

        val composite = CompositeLoom(
            plies = listOf(PLY to OneSeamLoom(ply) as Loom),
            dispatcher = StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, thrown -> unhandled += thrown },
            onPlyFailure = { raised += it },
        ).host(Pattern("upstream"))
        runCurrent()

        assertAll(
            { assertTrue(unhandled.isEmpty(), "nothing may reach the handler — that is the abort route") },
            // Two pumps collect `seam.peers` — the mirror and the re-announce — so the flow's failure ends
            // both. If a later change adds or removes a peers pump, this count is the thing that says so.
            { assertEquals(2, raised.size, "one report per peers pump the flow killed") },
            { assertTrue(raised.all { it.plyId == PLY }, "every report names the ply") },
            {
                assertEquals(
                    listOf(PlyReconcileException.Phase.PUMP_ENDED, PlyReconcileException.Phase.PUMP_ENDED),
                    raised.map { it.phase },
                    "the PUMP is over — not one delivery lost",
                )
            },
            { assertIs<IllegalStateException>(raised.firstOrNull()?.cause) },
            // The strand is dead; the seam is not. Its `state` pump never saw the failure, so the composite
            // is still Woven and still sends.
            { assertIs<SeamState.Woven>(composite.state.value) },
        )

        composite.broadcast(byteArrayOf(1))
        assertTrue(ply.delegate.broadcasts.isNotEmpty(), "a dead peers pump must not deafen the ply's sends")

        composite.close(CloseReason.Normal)
    }

    /**
     * A `Seam` whose `peers` flow delivers its current value and then **fails** — the shape a
     * consumer-authored `Seam` reaches by letting an exception out of a flow it built by hand, and the
     * shape no `onEach`-body guard can observe.
     */
    private class FailingPeersSeam(val delegate: FakeSeam) : Seam by delegate {
        @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
        override val peers: StateFlow<Set<PeerId>> = object : StateFlow<Set<PeerId>> {
            override val value: Set<PeerId> get() = delegate.peers.value
            override val replayCache: List<Set<PeerId>> get() = listOf(value)
            override suspend fun collect(collector: FlowCollector<Set<PeerId>>): Nothing {
                // A real collector always delivers its first value, so deliver it, then fail — proving the
                // pump ran and was killed rather than never having started.
                collector.emit(value)
                error(BOOM)
            }
        }
    }

    /** A [Loom] weaving one given ply seam. */
    private class OneSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    private companion object {
        const val PLY_NAME = "only"
        const val BOOM = "this consumer Seam's peers flow gave up"
        val PLY = PlyId(PLY_NAME)
    }
}
