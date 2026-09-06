package us.tractat.kuilt.test

import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Seam.peers] always holds [Seam.selfId] — **from construction onward, in every state** — and
 * [FakeSeam]'s constructor is the one place that obligation can be entered wrongly (#2536).
 *
 * `initialPeers` is an independent parameter, so a caller could hand the fake a roster naming only a
 * remote, or no one at all. Both are states no conforming seam reaches, and both read *backwards* in
 * the direction a consumer test cares about:
 *
 * - **The sentinel inverts.** `peers.value.size > 1` is the contract's documented sentinel for "at
 *   least one remote is connected" (see [Seam.peers]' initial-value invariant). Drop `selfId` and the
 *   whole roster under-counts by one: a seam with one live remote reports `1` and reads as *alone*,
 *   a seam that really is alone reports `0`. A consumer keying on the sentinel passes against this
 *   fake while describing the opposite of what production produces.
 * - **The fake disagrees with itself about who `selfId` is.** [FakeSeam.sendTo] refuses a self-send
 *   via `require(peer != selfId)` *before* consulting the roster (#2428), so a roster without
 *   `selfId` makes the refusal and the membership set name two different peers.
 *
 * ### Why this is not the #2432 guard
 * The `Torn` guard that landed in #2432 demands the roster be **exactly** `{ selfId }`, and only when
 * the seam is constructed already torn. This one demands `selfId ∈ peers` at **every** state — it is
 * strictly weaker per-state and strictly broader across states, so neither implies the other and
 * neither may be folded into the other. [tornAndLiveGuardsStayDistinct] is the standing check that
 * they did not get collapsed; `TestFakePeersCollapseOnTearTest` owns the `Torn` half.
 *
 * ### Why the controls are here
 * A `require(false)` would satisfy every refusal below while deleting the fake's two most common
 * shapes. [aRosterOfSelfAloneIsAccepted] and [aRosterOfSelfPlusARemoteIsAccepted] are what stops that,
 * and they are also what distinguishes this guard from one keyed on "has a remote".
 */
class FakeSeamRosterAlwaysHoldsSelfIdTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    // ── The two refused shapes (#2536) ────────────────────────────────────────────────────────

    @Test
    fun constructingAFakeSeamWhoseRosterNamesOnlyARemoteIsRefused() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FakeSeam(selfId = alice, initialPeers = setOf(bob))
        }
        val message = failure.message.orEmpty()
        assertAll(
            {
                assertTrue(
                    message.contains("selfId"),
                    "the refusal must name the obligation it enforces, not merely fail (got: $message)",
                )
            },
            {
                assertTrue(
                    message.contains(alice.value),
                    "the refusal must name the selfId that is missing, so an out-of-tree consumer can act " +
                        "on it without reading kuilt's source (got: $message)",
                )
            },
            {
                assertTrue(
                    message.contains(bob.value),
                    "the refusal must show the roster it was actually handed (got: $message)",
                )
            },
        )
    }

    /**
     * The other shape from #2536, and a separate test rather than another assertion: a guard written
     * `selfId in initialPeers || initialPeers.isEmpty()` refuses the roster above and admits this one,
     * and the two would be indistinguishable folded into one test.
     */
    @Test
    fun constructingAFakeSeamWithAnEmptyRosterIsRefused() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FakeSeam(selfId = alice, initialPeers = emptySet())
        }
        assertTrue(
            failure.message.orEmpty().contains("selfId"),
            "an empty roster is the same violation as a remote-only one — a seam always holds its own id " +
                "(got: ${failure.message})",
        )
    }

    // ── Controls: the shapes the guard must NOT take out ──────────────────────────────────────

    /** Also the shape the *default* `initialPeers` produces, so the guard can never fire on `FakeSeam()`. */
    @Test
    fun aRosterOfSelfAloneIsAccepted() {
        assertEquals(setOf(alice), FakeSeam(selfId = alice, initialPeers = setOf(alice)).peers.value)
    }

    @Test
    fun aRosterOfSelfPlusARemoteIsAccepted() {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))
        assertAll(
            { assertEquals(setOf(alice, bob), seam.peers.value) },
            {
                assertTrue(
                    seam.peers.value.size > 1,
                    "the sentinel this guard protects: one remote plus self is size 2, so `size > 1` holds " +
                        "— which is exactly what a roster missing selfId got wrong",
                )
            },
        )
    }

    /**
     * The guard is state-independent, so `Weaving` gets it too. Without this a guard written only for
     * the [SeamState.Woven] default would pass everything above.
     */
    @Test
    fun aWeavingSeamIsHeldToTheSameRosterObligation() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FakeSeam(selfId = alice, initialPeers = setOf(bob), initialState = SeamState.Weaving)
        }
        assertTrue(failure.message.orEmpty().contains("selfId"), "got: ${failure.message}")
    }

    // ── Anti-collapse: the #2432 Torn guard is a different guard ──────────────────────────────

    /**
     * `{ selfId, remote }` satisfies *this* guard, so the only thing that can refuse a `Torn`
     * construction of it is the #2432 guard. If the two were folded into one — this one kept, that one
     * dropped — the construction below would be accepted and this test would red.
     *
     * The converse direction is pinned by [constructingAFakeSeamWhoseRosterNamesOnlyARemoteIsRefused]
     * on a `Woven` seam, which the `Torn` guard cannot see.
     */
    @Test
    fun tornAndLiveGuardsStayDistinct() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FakeSeam(
                selfId = alice,
                initialPeers = setOf(alice, bob),
                initialState = SeamState.Torn(CloseReason.Normal),
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("Torn"),
            "a roster holding selfId passes the #2536 guard, so this must still be refused by the #2432 " +
                "Torn guard — a message that does not name Torn means the two collapsed into one " +
                "(got: ${failure.message})",
        )
    }
}
