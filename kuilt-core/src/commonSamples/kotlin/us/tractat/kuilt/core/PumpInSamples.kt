package us.tractat.kuilt.core

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Samples for [pumpIn] used by `@sample` KDoc tags.
 *
 * Compiled as part of commonTest, so a typo or an API change breaks the build rather than silently
 * producing stale documentation.
 */

/**
 * Collect a consumer-authored flow as a pump that neither a bad item nor a failing flow can kill —
 * the two ways a long-lived collector dies, both reported, neither fatal.
 */
@Suppress("unused")
internal fun samplePumpIn() = runTest {
    val applied = mutableListOf<String>()
    val reported = mutableListOf<PumpFailure>()

    // A consumer-authored flow that hands over one item the body cannot apply, and then fails outright.
    val updates = flow {
        emit("apply-me")
        emit("i-will-not-apply")
        error("…and then the flow itself gave up")
    }

    val pump = updates.pumpIn(
        scope = backgroundScope,
        // ITEM: that update was lost, the pump lives. UPSTREAM: the pump is over — say so, loudly.
        onFailure = { half, _ ->
            reported += half
            // Each update stands alone, so one that failed is no reason to stop applying the rest.
            PumpVerdict.CONTINUE
        },
        // What a coroutine census calls this pump when it is the one that wedged.
        name = "sample-updates",
    ) { update ->
        if (update == "i-will-not-apply") error("this update could not be applied")
        applied += update
    }
    pump.join()

    assertEquals(listOf("apply-me"), applied)
    assertEquals(listOf(PumpFailure.ITEM, PumpFailure.UPSTREAM), reported)
}

/**
 * Stop a pump whose items build on each other at the first one that fails, so nothing is applied on
 * top of a half-applied step.
 */
@Suppress("unused")
internal fun samplePumpInStop() = runTest {
    val ledger = mutableListOf<Int>()

    // Deltas, not whole values: each one only makes sense on top of every delta before it.
    val deltas = flowOf(+5, -99, +3)

    val pump = deltas.pumpIn(
        scope = backgroundScope,
        // The ledger is now missing a step, so every later delta would be applied to the wrong total.
        onFailure = { _, _ -> PumpVerdict.STOP },
        name = "sample-ledger",
    ) { delta ->
        require(delta > -10) { "this delta could not be applied" }
        ledger += delta
    }
    pump.join()

    assertEquals(listOf(5), ledger)
    assertFalse(pump.isCancelled)
}
