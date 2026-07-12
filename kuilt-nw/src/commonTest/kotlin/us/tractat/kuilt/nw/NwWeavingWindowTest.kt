package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The radio-fabric Weaving→Woven window for [NwSeam], mandated by [SeamConformanceSuite]'s
 * "Weaving timing invariant" for async fabrics (analogue of `DelayedWovenLoomTest`).
 *
 * [NwLoom.weave] awaits the first peer before returning, so the seam is already `Woven` by then —
 * the Weaving window is only observable at the [NwSeam] level, which this drives directly over
 * [FakeNwApi]. Asserts the contract-correct behaviour:
 *  - a freshly-constructed seam (no connection yet) is `Weaving`;
 *  - a `broadcast` issued while `Weaving` does NOT throw (best-effort per the Seam contract — a
 *    no-op before Woven, never buffered-and-guaranteed);
 *  - the seam flips to `Woven` when its first peer resolves;
 *  - a frame broadcast AFTER Woven is delivered.
 */
class NwWeavingWindowTest {

    private companion object {
        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }

        /** A per-seam child scope of backgroundScope with its OWN Job — one seam's teardown can't cancel the other. */
        fun TestScope.seamScope(): CoroutineScope =
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
    }

    @Test
    fun startsWeavingBroadcastDoesNotThrowThenWovenDelivers() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(us.tractat.kuilt.core.PeerId("peer-0"), apiA, seamScope())
        val seamB = NwSeam(us.tractat.kuilt.core.PeerId("peer-1"), apiB, seamScope())

        val receivedB = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { receivedB += it } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        testScheduler.runCurrent()

        // Before any connection, seamA is Weaving — and a Weaving-time broadcast must NOT throw.
        assertIs<SeamState.Weaving>(seamA.state.value, "seam starts Weaving before any peer")
        seamA.broadcast("weaving".encodeToByteArray()) // best-effort no-op; must not throw

        // Dial B → identity exchange resolves the first peer → Woven.
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        pumpUntil { seamA.state.value is SeamState.Woven }
        assertIs<SeamState.Woven>(seamA.state.value, "seam flips to Woven once the first peer resolves")

        // A frame broadcast AFTER Woven is delivered.
        seamA.broadcast("woven".encodeToByteArray())
        pumpUntil { receivedB.isNotEmpty() }

        assertAll(
            { assertTrue(seamA.state.value is SeamState.Woven, "still Woven") },
            { assertEquals("woven", receivedB.single().decodeToString(), "post-Woven broadcast delivered") },
            { assertEquals(seamA.selfId, receivedB.single().sender, "attributed to A") },
        )
    }
}
