package us.tractat.kuilt.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-hop origin-spoofing rule, shared by every point that accepts a relay frame off a
 * fabric. `origin` rides *inside* a forgeable envelope, so it is checked against the frame's
 * fabric-stamped sender before anything trusts it.
 */
class RelayFirstHopTest {

    @Test
    fun `a spoke may speak only for itself`() {
        assertAll(
            // Positive control first: a rule that rejected everything would satisfy every
            // negative below.
            { assertTrue(validFirstHop(sender = "a", origin = "a", trusted = emptySet())) },
            { assertFalse(validFirstHop(sender = "a", origin = "b", trusted = emptySet())) },
        )
    }

    @Test
    fun `a trusted relayer is believed about a third party`() {
        val trusted = setOf("s1", "s2")
        assertAll(
            { assertTrue(validFirstHop(sender = "s1", origin = "b", trusted = trusted)) },
            { assertTrue(validFirstHop(sender = "s1", origin = "s1", trusted = trusted)) },
            // …but a spoke is still not, even when the origin it names is trusted.
            { assertFalse(validFirstHop(sender = "b", origin = "s1", trusted = trusted)) },
        )
    }

    /**
     * The session layer's instantiation. With no trusted relayers the rule degenerates to
     * `origin == sender` — pinned here so the degeneracy is a *stated* property rather than an
     * accident a later edit could silently change.
     */
    @Test
    fun `an empty trusted set reduces the rule to origin equals sender`() {
        assertAll(
            { assertTrue(validFirstHop(sender = "a", origin = "a", trusted = emptySet())) },
            { assertFalse(validFirstHop(sender = "a", origin = "b", trusted = emptySet())) },
            { assertFalse(validFirstHop(sender = "a", origin = "c", trusted = emptySet())) },
        )
    }
}
