package us.tractat.kuilt.core

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals

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
        onFailure = { half, _ -> reported += half },
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
