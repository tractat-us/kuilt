package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Soak test composing lifecycle flap (outer) over frame faults (inner).
 *
 * Verifies that every frame sent while [SeamState.Woven] is eventually delivered,
 * and that delivery order is preserved, even when the seam is blipping between sends.
 * Uses [FaultProfile.Healthy] (no frame drops) combined with lifecycle flaps to keep
 * the test deterministic and fast under virtual time.
 *
 * The composition is: `FlakyLifecycleSeam(FaultySeam(raw), scope)` — lifecycle gate
 * outer, frame-fault layer inner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilienceSoakTest {

    @Test
    fun `frames sent while Woven are eventually delivered under flap plus frame fault layer`() = runTest {
        val mem = InMemoryLoom()
        // Inner frame-fault layer (Healthy profile = no drops), outer lifecycle flaps.
        val faultyA = FaultySeam(mem.host(Pattern("a")), backgroundScope, FaultProfile.Healthy)
        val a = FlakyLifecycleSeam(faultyA, backgroundScope)
        val b = mem.join(InMemoryTag("b"))
        // Allow the background peers-watcher coroutine to update _peers with b's id.
        testScheduler.runCurrent()

        val received = async { b.incoming.take(5).toList() }

        // Send 5 frames, each only while Woven, blipping between sends.
        for (i in 1..5) {
            if (a.state.value !is SeamState.Woven) a.recover()
            a.broadcast(byteArrayOf(i.toByte()))
            a.blip(weavingFor = 50.milliseconds)
        }

        val frames = received.await().map { it.payload.single().toInt() }
        assertEquals(listOf(1, 2, 3, 4, 5), frames, "all Woven-sent frames converge in order")
    }

    @Test
    fun `FlapSchedule driven seam delivers frames sent during Woven windows`() = runTest {
        val mem = InMemoryLoom()
        val a = FlakyLifecycleSeam(mem.host(Pattern("a")), backgroundScope)
        val b = mem.join(InMemoryTag("b"))
        testScheduler.runCurrent()

        val received = async { b.incoming.take(3).toList() }

        // Drive a FlapSchedule in the background — seam will oscillate Woven/Weaving.
        val schedule = FlapSchedule(
            seed = 7L,
            meanUptime = 100.milliseconds,
            meanDowntime = 30.milliseconds,
            giveUpAfter = 0, // infinite — we cancel when done
        )
        val driveJob = a.drive(schedule)

        // Send 3 frames, each only when Woven, with enough time between them for the
        // schedule to potentially flap in between.
        for (i in 1..3) {
            // Wait until Woven before sending.
            a.state.take(1).toList()  // just prime the flow; send immediately if already Woven
            if (a.state.value is SeamState.Woven) {
                a.broadcast(byteArrayOf(i.toByte()))
            } else {
                a.recover()
                a.broadcast(byteArrayOf(i.toByte()))
            }
            // Small virtual-time gap to let the schedule flap.
            testScheduler.advanceTimeBy(50)
        }

        driveJob.cancel()
        val frames = received.await().map { it.payload.single().toInt() }
        assertEquals(listOf(1, 2, 3), frames)
    }
}
