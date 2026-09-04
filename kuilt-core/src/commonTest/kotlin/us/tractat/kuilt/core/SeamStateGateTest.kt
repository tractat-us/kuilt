package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deterministic unit tests for [SeamStateGate]'s latch semantics. The real-threaded stress that a
 * concurrent `update()`/`tear()` never leaves the state off `Torn` lives in the gated probe
 * `SeamStateGateConcurrencyTest` (JVM, real dispatcher) — the invariant here is single-threaded and
 * exercises the *rules*, not the race.
 */
class SeamStateGateTest {

    @Test
    fun initialStateIsWhatWasConstructed() {
        val gate = SeamStateGate(SeamState.Weaving)
        assertIs<SeamState.Weaving>(gate.state.value)
    }

    @Test
    fun updateMovesStateWhileNotTorn() {
        val gate = SeamStateGate(SeamState.Weaving)
        gate.update(SeamState.Woven)
        assertIs<SeamState.Woven>(gate.state.value)
    }

    @Test
    fun tearPublishesTornWithReasonAndReturnsTrueForTheWinner() {
        val gate = SeamStateGate(SeamState.Woven)
        val won = gate.tear(CloseReason.Normal)
        assertTrue(won, "the first tear() must win")
        val state = gate.state.value
        assertIs<SeamState.Torn>(state)
        assertEquals(CloseReason.Normal, state.reason)
    }

    @Test
    fun tearIsSingleShot() {
        val gate = SeamStateGate(SeamState.Woven)
        assertTrue(gate.tear(CloseReason.Normal), "first tear wins")
        assertFalse(gate.tear(CloseReason.RemoteRequested), "second tear must lose")
        // The reason must remain the winner's — a losing tear never overwrites it.
        val state = gate.state.value
        assertIs<SeamState.Torn>(state)
        assertEquals(CloseReason.Normal, state.reason, "a losing tear() must not overwrite the reason")
    }

    @Test
    fun updateAfterTearIsANoOp() {
        val gate = SeamStateGate(SeamState.Woven)
        gate.tear(CloseReason.Normal)
        gate.update(SeamState.Woven)
        gate.update(SeamState.Weaving)
        assertIs<SeamState.Torn>(gate.state.value, "no update() may move the state off a latched Torn")
    }

    @Test
    fun updateNeverLatchesOnlyTearDoes() {
        // The gate latches on the close DECISION, not on any published value: only tear() latches.
        // Shown with the recoverable states, which since #1803 are the only ones update() accepts —
        // see updateRefusesTorn for why demonstrating it with a Torn is no longer possible.
        val gate = SeamStateGate(SeamState.Weaving)
        gate.update(SeamState.Woven)
        gate.update(SeamState.Weaving)
        gate.update(SeamState.Woven)
        assertIs<SeamState.Woven>(gate.state.value, "update() must move freely while un-latched")
        // Still un-latched after all of that: a tear can still win, which is what "never latches" means.
        assertTrue(gate.tear(CloseReason.Normal), "no update() may have latched the gate")
    }

    @Test
    fun updateRefusesTorn() {
        // This test used to assert the OPPOSITE — that a Torn passed to update() publishes without
        // latching and can be superseded — on the reasoning that the gate's invariant is
        // value-independent and production never derives a Torn anyway. That was pinning the
        // loophole rather than closing it: `update(Torn)` published the terminal state with
        // `latched == false`, so the very next update() clobbered it. Which is #1803 — the exact bug
        // this class exists to prevent — reachable through the front door of the class, and QUIETER
        // than the original, because the seam looks correctly gated. Now refused outright (#1803).
        val gate = SeamStateGate(SeamState.Woven)
        assertFailsWith<IllegalArgumentException> { gate.update(SeamState.Torn(CloseReason.RemoteRequested)) }
        // The refusal must leave the gate exactly as it was — neither published nor latched — or it
        // would trade a clobber for a half-applied write.
        assertIs<SeamState.Woven>(gate.state.value, "a refused update() must not have published anything")
        assertTrue(gate.tear(CloseReason.Normal), "a refused update() must not have latched the gate")
    }
}
