package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

        val frames = received.await().map { it.byteAt(0).toInt() }
        assertEquals(listOf(1, 2, 3, 4, 5), frames, "all Woven-sent frames converge in order")
    }

    @Test
    fun `FlapSchedule driven seam delivers frames sent during Woven windows and genuinely flaps`() = runTest {
        val mem = InMemoryLoom()
        val a = FlakyLifecycleSeam(mem.host(Pattern("a")), backgroundScope)
        val b = mem.join(InMemoryTag("b"))
        testScheduler.runCurrent()

        val received = async { b.incoming.take(3).toList() }

        // Collect state transitions to prove real flaps occurred.
        val stateHistory = mutableListOf<SeamState>()
        val historyJob = launch { a.state.collect { stateHistory += it } }
        testScheduler.runCurrent() // subscribe historyJob before drive starts

        // Drive flap schedule: short uptime / long downtime ensures Weaving windows
        // are wide enough to observe between sends.
        val driveJob = a.drive(
            FlapSchedule(
                seed = 7L,
                meanUptime = 20.milliseconds,
                meanDowntime = 80.milliseconds,
                giveUpAfter = 0,
            ),
        )

        // Send 3 frames, each only while Woven. Advance virtual time in small steps
        // so the background drive loop genuinely flaps between sends.
        for (i in 1..3) {
            // Advance past the current uptime window so the seam has had a chance to flap.
            testScheduler.advanceTimeBy(30)
            testScheduler.runCurrent()
            // If currently Weaving, advance through the downtime window to reach Woven again.
            while (a.state.value !is SeamState.Woven) {
                testScheduler.advanceTimeBy(10)
                testScheduler.runCurrent()
            }
            a.broadcast(byteArrayOf(i.toByte()))
            testScheduler.runCurrent()
        }

        driveJob.cancel()
        historyJob.cancel()
        val frames = received.await().map { it.byteAt(0).toInt() }

        assertAll(
            // (a) All 3 frames arrived in order.
            { assertEquals(listOf(1, 2, 3), frames, "all Woven-gated frames delivered in order") },
            // (b) The schedule genuinely flapped — seam passed through Weaving at least once.
            {
                assertTrue(
                    stateHistory.any { it is SeamState.Weaving },
                    "seam must have passed through Weaving at least once (real flap, not bypassed)",
                )
            },
        )
    }
}

