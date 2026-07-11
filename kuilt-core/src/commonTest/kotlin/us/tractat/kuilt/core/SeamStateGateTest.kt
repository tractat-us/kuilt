package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
        // The gate latches on the close DECISION, not the Torn VALUE: only tear() latches; update()
        // never does, whatever value it carries. Production rollups only ever publish recoverable
        // Woven/Weaving through update() (all-plies-torn is Weaving, #1367), so a derived Torn no
        // longer occurs — but the gate's invariant is value-independent, pinned here directly: even
        // a Torn passed to update() does not latch and can be superseded.
        val gate = SeamStateGate(SeamState.Woven)
        gate.update(SeamState.Torn(CloseReason.RemoteRequested))
        assertIs<SeamState.Torn>(gate.state.value)
        gate.update(SeamState.Woven)
        assertIs<SeamState.Woven>(gate.state.value, "update() must NOT latch on any value — only tear() latches")
    }
}
