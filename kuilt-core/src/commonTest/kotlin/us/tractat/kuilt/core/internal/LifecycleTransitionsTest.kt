package us.tractat.kuilt.core.internal

import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Synchronous unit tests for the pure [LifecycleTransitions] functions.
 *
 * No coroutines, no `StateFlow`, no test scheduler — each assertion is a
 * direct call that returns a value. All platforms run this without any
 * test-framework magic beyond `@Test`.
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
        assertEquals(torn, onEnterWeaving(torn))
    }

    @Test
    fun onEnterWeaving_fromTorn_preservesTornInstance() {
        val torn = SeamState.Torn(CloseReason.Normal)
        val result = onEnterWeaving(torn)
        // Same reference — the function returns current unchanged.
        assertEquals(torn, result)
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
        assertEquals(torn, onRecover(torn))
    }

    @Test
    fun onRecover_fromTorn_preservesReason() {
        val torn = SeamState.Torn(CloseReason.Normal)
        val result = onRecover(torn)
        assertIs<SeamState.Torn>(result)
        assertEquals(CloseReason.Normal, result.reason)
    }

    // ── onTear ────────────────────────────────────────────────────────────────

    @Test
    fun onTear_fromWoven_returnsTornWithReason() {
        val result = onTear(SeamState.Woven, CloseReason.Unreachable)
        assertIs<SeamState.Torn>(result)
        assertEquals(CloseReason.Unreachable, result.reason)
    }

    @Test
    fun onTear_fromWeaving_returnsTornWithReason() {
        val result = onTear(SeamState.Weaving, CloseReason.Normal)
        assertIs<SeamState.Torn>(result)
        assertEquals(CloseReason.Normal, result.reason)
    }

    @Test
    fun onTear_alreadyTorn_idempotent() {
        val original = SeamState.Torn(CloseReason.Unreachable)
        val result = onTear(original, CloseReason.Normal)
        // First tear wins — reason is not overwritten.
        assertEquals(original, result)
        assertEquals(CloseReason.Unreachable, (result as SeamState.Torn).reason)
    }

    @Test
    fun onTear_alreadyTorn_preservesOriginalReason() {
        val original = SeamState.Torn(CloseReason.Normal)
        val result = onTear(original, CloseReason.Unreachable)
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

    @Test
    fun wovenThenTorn_terminal() {
        val s0 = SeamState.Woven
        val s1 = onTear(s0, CloseReason.Unreachable)
        val s2 = onEnterWeaving(s1) // no-op
        val s3 = onRecover(s2)      // no-op
        assertIs<SeamState.Torn>(s3)
        assertEquals(CloseReason.Unreachable, s3.reason)
    }

    @Test
    fun weavingThenTorn_terminal() {
        val s0 = onEnterWeaving(SeamState.Woven)
        val s1 = onTear(s0, CloseReason.Normal)
        val s2 = onRecover(s1) // no-op after torn
        assertIs<SeamState.Torn>(s2)
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
