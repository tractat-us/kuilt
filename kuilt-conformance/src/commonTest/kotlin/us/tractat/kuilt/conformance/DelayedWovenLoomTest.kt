package us.tractat.kuilt.conformance

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies [DelayedWovenLoom] accurately simulates the radio-fabric timing window:
 * - Seam starts [SeamState.Weaving] after `weave()` returns.
 * - Transitions to [SeamState.Woven] only when [DelayedWovenSeam.markWoven] fires.
 * - Frames sent while [SeamState.Weaving] are still delivered (observable, not silently dropped).
 */
class DelayedWovenLoomTest {

    @Test
    fun seamStartsWeaving() =
        runTest {
            val loom = DelayedWovenLoom()
            val seam = loom.host(Pattern("test")) as DelayedWovenSeam
            assertIs<SeamState.Weaving>(seam.state.value, "seam should start Weaving")
        }

    @Test
    fun markWovenTransitionsToWoven() =
        runTest {
            val loom = DelayedWovenLoom()
            val seam = loom.host(Pattern("test")) as DelayedWovenSeam
            seam.markWoven()
            assertIs<SeamState.Woven>(seam.state.value, "seam should be Woven after markWoven()")
        }

    @Test
    fun framesSentWhileWeavingAreDeliveredOnceWoven() =
        runTest {
            val loom = DelayedWovenLoom()
            val host = loom.host(Pattern("host")) as DelayedWovenSeam
            val joiner = loom.join(InMemoryTag("joiner")) as DelayedWovenSeam

            // Both seams are Weaving at this point.
            assertIs<SeamState.Weaving>(host.state.value)
            assertIs<SeamState.Weaving>(joiner.state.value)

            // Collect the incoming on the joiner side before the frame arrives.
            val received = async { joiner.incoming.take(1).toList() }

            // Send while Weaving — this is the silent-drop bug class under test.
            host.broadcast(byteArrayOf(42))

            // Transition to Woven — frame already in-flight but channel still open.
            host.markWoven()
            joiner.markWoven()

            // Frame sent while Weaving must arrive (DelayedWovenLoom does not gate sends).
            val frames = received.await()
            assertIs<SeamState.Woven>(joiner.state.value)
            assertTrue(frames.size == 1, "Expected 1 frame, got ${frames.size}")
            assertTrue(frames[0].payload.contentEquals(byteArrayOf(42)), "Payload mismatch")
        }

    @Test
    fun stateFlowEmitsWovenTransition() =
        runTest {
            val loom = DelayedWovenLoom()
            val seam = loom.host(Pattern("test")) as DelayedWovenSeam

            val wovenState = async { seam.state.first { it is SeamState.Woven } }
            seam.markWoven()

            assertIs<SeamState.Woven>(wovenState.await(), "state flow must emit Woven")
        }
}
