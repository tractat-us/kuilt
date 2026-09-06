package us.tractat.kuilt.test.internal

import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Synchronous unit tests for the pure [LifecycleTransitions] functions.
 *
 * No coroutines, no `StateFlow`, no test scheduler — each assertion is a
 * direct call that returns a value. All platforms run this without any
 * test-framework magic beyond `@Test`.
 *
 * ## The no-op arms assert IDENTITY, and that is load-bearing (#2633)
 *
 * `FlakyLifecycleSeam` decides whether to publish at all with `next === current`, and that identity
 * is the only thing keeping a [SeamState.Torn] out of `SeamStateGate.update`, which rejects one. So
 * the `fromTorn` arms use [assertSame]. They used [assertEquals] — one of them named
 * `preservesTornInstance` while checking no such thing — and since [SeamState.Torn] is a data class,
 * an equality assertion passes on a freshly constructed copy: the exact mutation that would break
 * the caller went undetected. There is no `onTear` arm any more; the terminal transition is
 * `SeamStateGate.tear`'s, and its single-shot/reason-preservation properties are pinned by
 * `SeamStateGateTest.tearIsSingleShot` and, at this class's own level, by
 * `FlakyLifecycleSeamTest`'s repeat-tear arm.
 */
class LifecycleTransitionsTest {

    // ── initialLifecycleState ─────────────────────────────────────────────────

    @Test
    fun initialLifecycleState_delegateWoven_returnsWoven() {
        assertEquals(SeamState.Woven, initialLifecycleState(SeamState.Woven))
    }

    @Test
    fun initialLifecycleState_delegateWeaving_returnsWoven() {
        // A delegate in the middle of connecting resolves to Woven for the wrapper.
        assertEquals(SeamState.Woven, initialLifecycleState(SeamState.Weaving))
    }

    @Test
    fun initialLifecycleState_delegateTorn_propagatesTorn() {
        val reason = CloseReason.Unreachable
        assertEquals(SeamState.Torn(reason), initialLifecycleState(SeamState.Torn(reason)))
    }

    @Test
    fun initialLifecycleState_delegateTorn_preservesReason() {
        val result = initialLifecycleState(SeamState.Torn(CloseReason.Normal))
        assertIs<SeamState.Torn>(result)
        assertEquals(CloseReason.Normal, result.reason)
    }

    // ── onEnterWeaving ────────────────────────────────────────────────────────

    @Test
    fun onEnterWeaving_fromWoven_transitionsToWeaving() {
        assertEquals(SeamState.Weaving, onEnterWeaving(SeamState.Woven))
    }

    @Test
    fun onEnterWeaving_alreadyWeaving_noOp() {
        val current = SeamState.Weaving
        assertEquals(current, onEnterWeaving(current))
    }

    @Test
    fun onEnterWeaving_fromTorn_noOp() {
        val torn = SeamState.Torn(CloseReason.Unreachable)
        assertSame(torn, onEnterWeaving(torn))
    }

    @Test
    fun onEnterWeaving_fromTorn_preservesTornInstance() {
        val torn = SeamState.Torn(CloseReason.Normal)
        val result = onEnterWeaving(torn)
        // Same REFERENCE, not merely an equal value: `FlakyLifecycleSeam.enterWeaving` returns early
        // on `next === current`, so an equal-but-fresh Torn here would reach `SeamStateGate.update`,
        // which rejects one. `assertEquals` cannot see that — `Torn` is a data class.
        assertSame(torn, result)
        assertIs<SeamState.Torn>(result)
        assertEquals(CloseReason.Normal, result.reason)
    }

    // ── onRecover ─────────────────────────────────────────────────────────────

    @Test
    fun onRecover_fromWeaving_transitionsToWoven() {
        assertEquals(SeamState.Woven, onRecover(SeamState.Weaving))
    }

    @Test
    fun onRecover_alreadyWoven_noOp() {
        assertEquals(SeamState.Woven, onRecover(SeamState.Woven))
    }

    @Test
    fun onRecover_fromTorn_noOp() {
        val torn = SeamState.Torn(CloseReason.Unreachable)
        assertSame(torn, onRecover(torn))
    }

    @Test
    fun onRecover_fromTorn_preservesReason() {
        val torn = SeamState.Torn(CloseReason.Normal)
        val result = onRecover(torn)
        // Identity, for `onEnterWeaving_fromTorn_preservesTornInstance`'s reason — `recover()` takes
        // the same `next === current` early return.
        assertSame(torn, result)
        assertIs<SeamState.Torn>(result)
        assertEquals(CloseReason.Normal, result.reason)
    }

    // ── Transition sequences ──────────────────────────────────────────────────

    @Test
    fun wovenThenWeavingThenWoven_roundTrip() {
        val s0 = SeamState.Woven
        val s1 = onEnterWeaving(s0)
        val s2 = onRecover(s1)
        assertIs<SeamState.Woven>(s2)
    }

    /**
     * Torn is terminal for BOTH recoverable transitions, and each of them hands back the very same
     * instance — which is what lets the caller's `next === current` early return keep a `Torn` away
     * from `SeamStateGate.update`. `Torn` is constructed directly here: it is now published by
     * `SeamStateGate.tear`, and there is no pure `onTear` to reach it through.
     */
    @Test
    fun tornIsTerminalForBothRecoverableTransitions() {
        val torn = SeamState.Torn(CloseReason.Unreachable)
        val afterEnterWeaving = onEnterWeaving(torn) // no-op
        val afterRecover = onRecover(afterEnterWeaving) // no-op
        assertAll(
            { assertSame(torn, afterEnterWeaving, "onEnterWeaving must hand back the SAME Torn") },
            { assertSame(torn, afterRecover, "onRecover must hand back the SAME Torn") },
            { assertEquals(CloseReason.Unreachable, (afterRecover as SeamState.Torn).reason) },
        )
    }

    @Test
    fun multipleRecoverCalls_onlyFirstTransitions() {
        val s0 = SeamState.Weaving
        val s1 = onRecover(s0)
        val s2 = onRecover(s1)
        assertIs<SeamState.Woven>(s1)
        assertIs<SeamState.Woven>(s2)
    }

    @Test
    fun multipleEnterWeavingCalls_onlyFirstTransitions() {
        val s0 = SeamState.Woven
        val s1 = onEnterWeaving(s0)
        val s2 = onEnterWeaving(s1)
        assertIs<SeamState.Weaving>(s1)
        assertIs<SeamState.Weaving>(s2)
    }
}
