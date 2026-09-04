package us.tractat.kuilt.core

import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Samples for [SeamStateGate] used by `@sample` KDoc tags.
 *
 * Compiled as part of commonTest, so a typo or an API change breaks the build rather than silently
 * producing stale documentation.
 */

/**
 * A fabric's two state writers — a transport callback promoting the seam, and `close()` tearing it
 * down — routed through one gate, so the late promotion that used to clobber the terminal `Torn`
 * is a no-op instead.
 */
@Suppress("unused")
internal fun sampleSeamStateGate() = runTest {
    val gate = SeamStateGate(SeamState.Weaving)

    // The transport callback's promotion. Unconditional: `Woven` over `Woven` conflates, and once
    // the gate has latched it cannot land at all — so no `if (state.value is Weaving)` guard, which
    // was never a promotion rule but the read half of a race.
    gate.update(SeamState.Woven)
    assertIs<SeamState.Woven>(gate.state.value)

    // The close decision. Single-shot: `true` for the one winning caller, so this IS the seam's
    // terminal latch and it needs no separate `closed` atomic beside it.
    assertTrue(gate.tear(CloseReason.Normal))
    assertEquals(false, gate.tear(CloseReason.RemoteRequested), "a second tear loses; the first reason stands")

    // The whole point: a promotion still in flight when the tear landed. Before the gate this write
    // stamped `Woven` over the terminal `Torn` — permanently, because both writers then retire and
    // every `state.first { it is Torn }` waiter hangs forever.
    gate.update(SeamState.Woven)
    assertIs<SeamState.Torn>(gate.state.value)
}
