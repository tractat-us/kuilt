package us.tractat.kuilt.test

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `selfId ∈ peers` is an **invariant**, not a construction-time snapshot (#2546).
 *
 * [FakeSeamRosterAlwaysHoldsSelfIdTest] pins the constructor's `require` (#2536). That guard reads
 * the roster once, at construction, and then never again — so two doors reach the *bit-identical*
 * state it refuses, from a consumer's test **body** instead of its fixture:
 *
 * - **[FakeSeam.removePeer] applied to `selfId`.** `FakeSeam(selfId = a, initialPeers = setOf(a, b))`
 *   followed by `removePeer(a)` leaves `peers == { b }` with [SeamState.Woven] still latched. The
 *   inversion #2536 exists to prevent fires exactly as it describes: one live remote, and
 *   `peers.value.size > 1` — the contract's documented sentinel for "at least one remote is
 *   connected" — reads `false`, so the seam reads *alone*.
 * - **The caller's own `Set` instance, mutated afterwards.** `_peers` was constructed *around*
 *   `initialPeers` rather than around a copy of it, so a caller holding a [MutableSet] reference
 *   could drop `selfId` a line after the `require` passed, with no [FakeSeam] method called at all.
 *
 * Both are states no conforming seam reaches, which makes every consumer assertion written against
 * them unfalsifiable in the useful direction — the permissive-fake shape of #2432/#2443/#2536, one
 * arm over.
 *
 * ### Why the controls are here
 * `require(false)` in [FakeSeam.removePeer] would satisfy every refusal below while deleting the
 * helper's entire purpose, and `_peers = MutableStateFlow(setOf(selfId))` would satisfy both door-2
 * arms while discarding the caller's remotes. [removePeerStillRemovesARemote],
 * [removingAPeerTheRosterNeverHeldStaysASilentNoOp] and
 * [aRosterPassedAsAMutableSetIsStillTheRosterTheSeamReports] are what stop that.
 */
class FakeSeamRosterHoleAfterConstructionTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")

    // ── Door 1: removePeer(selfId) ────────────────────────────────────────────────────────────

    @Test
    fun removePeerRefusesSelfId() {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))

        val failure = assertFailsWith<IllegalArgumentException> { seam.removePeer(alice) }

        assertAll(
            {
                assertTrue(
                    failure.message.orEmpty().contains("selfId"),
                    "the refusal must name the obligation it enforces, not merely fail " +
                        "(got: ${failure.message})",
                )
            },
            {
                assertTrue(
                    failure.message.orEmpty().contains("tear"),
                    "an out-of-tree consumer reaching this needs the collapse it actually wanted — " +
                        "`tear()` is the only transition that legitimately shrinks the roster " +
                        "(got: ${failure.message})",
                )
            },
            {
                assertEquals(
                    setOf(alice, bob),
                    seam.peers.value,
                    "a refused removePeer must leave the roster untouched, not half-applied",
                )
            },
            {
                assertTrue(
                    seam.peers.value.size > 1,
                    "the sentinel this guard protects: one live remote plus self is size 2. Applying " +
                        "the removal would make it 1 and the seam would read as alone",
                )
            },
        )
    }

    /**
     * The spelling the issue names as the realistic misuse: a consumer simulating a full membership
     * drain writes the obvious loop and gets `emptySet()` — every downstream assertion on
     * `selfId in peers` or `size > 1` then describes something production cannot produce.
     *
     * The assertions are deliberately **iteration-order-independent**. `peers.value` is a `Set`, so
     * the snapshot the loop walks is in hash order, which differs between the JVM and Kotlin/Native;
     * whichever id comes first, the loop reaches `alice` and throws, and `alice` is never removed.
     * Nothing here keys on whether `bob` was dropped before the throw.
     */
    @Test
    fun theNaturalFullDrainLoopIsRefusedRatherThanEmptyingTheRoster() {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))

        assertFailsWith<IllegalArgumentException> {
            seam.peers.value.forEach { seam.removePeer(it) }
        }

        assertAll(
            {
                assertTrue(
                    alice in seam.peers.value,
                    "Seam.peers holds selfId in every state — a drain loop must not be able to empty it",
                )
            },
            {
                assertTrue(
                    seam.state.value is SeamState.Woven,
                    "the drain leaves the seam Woven (that is what makes it a membership drain rather " +
                        "than a tear), so the roster hole would be reachable in a live state",
                )
            },
        )
    }

    /**
     * [FakeSeam.sendTo] has refused `selfId` since #2428. Until [FakeSeam.removePeer] does too, the
     * fake disagrees with itself on the same axis in two places: it refuses to *address* self and
     * accepts *forgetting* self.
     */
    @Test
    fun sendToAndRemovePeerAgreeThatSelfIsNotARemote() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))

        val sendFailure = assertFailsWith<IllegalArgumentException> {
            seam.sendTo(alice, byteArrayOf(1))
        }
        val removeFailure = assertFailsWith<IllegalArgumentException> { seam.removePeer(alice) }

        assertAll(
            { assertTrue(sendFailure.message.orEmpty().isNotEmpty(), "sendTo's refusal must explain itself") },
            { assertTrue(removeFailure.message.orEmpty().isNotEmpty(), "removePeer's refusal must explain itself") },
        )
    }

    // ── Door 1 controls: the shapes the guard must NOT take out ───────────────────────────────

    @Test
    fun removePeerStillRemovesARemote() {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))

        seam.removePeer(bob)

        assertEquals(
            setOf(alice),
            seam.peers.value,
            "the membership drain this helper exists to model must still work — a guard that refused " +
                "every peer would satisfy every refusal above and delete removePeer's purpose",
        )
    }

    /**
     * A guard written `require(peer != selfId && peer in peers.value)` would refuse this, changing an
     * unrelated pre-existing behaviour under cover of the fix. Removing an absent peer stays a no-op.
     */
    @Test
    fun removingAPeerTheRosterNeverHeldStaysASilentNoOp() {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))

        seam.removePeer(carol)

        assertEquals(setOf(alice, bob), seam.peers.value, "removing an absent peer is a no-op, not a refusal")
    }

    // ── Door 2: the roster is copied, not retained ────────────────────────────────────────────

    /**
     * The mutation is the whole test. An arm that constructs from a [MutableSet] and never touches it
     * again passes with or without the defensive copy, and would be a vacuous fixture in the shape
     * this repo keeps re-finding.
     */
    @Test
    fun droppingSelfIdFromTheCallersOwnSetAfterConstructionCannotHoleTheRoster() {
        val callersRoster = mutableSetOf(alice, bob)
        val seam = FakeSeam(selfId = alice, initialPeers = callersRoster)

        callersRoster.remove(alice)

        assertAll(
            {
                assertTrue(
                    alice in seam.peers.value,
                    "the constructor's require passed a line earlier; retaining the caller's instance " +
                        "would let this line reach the exact state it refused, with no FakeSeam method " +
                        "called at all",
                )
            },
            {
                assertEquals(
                    setOf(alice, bob),
                    seam.peers.value,
                    "the roster the seam reports is the one it was handed, frozen at construction",
                )
            },
        )
    }

    /**
     * The other direction of the same aliasing, and a separate test rather than another assertion:
     * a copy taken as `setOf(selfId)` would satisfy the arm above while failing this one, and the two
     * would be indistinguishable folded together. It also has a *behavioural* consequence rather than
     * only a reading one — see [aForgedPeerIsNotRoutable].
     */
    @Test
    fun addingToTheCallersOwnSetAfterConstructionCannotForgeAPeer() {
        val callersRoster = mutableSetOf(alice, bob)
        val seam = FakeSeam(selfId = alice, initialPeers = callersRoster)

        callersRoster.add(carol)

        assertEquals(
            setOf(alice, bob),
            seam.peers.value,
            "peers is a StateFlow: a roster that changes underneath it changes the flow's value with " +
                "no emission at all, so a collector never observes what peers.value now reports",
        )
    }

    /** The aliasing is not merely a misreading of `peers.value` — it re-routes [Seam] calls. */
    @Test
    fun aForgedPeerIsNotRoutable() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val callersRoster = mutableSetOf(alice, bob)
        val seam = FakeSeam(selfId = alice, initialPeers = callersRoster)

        callersRoster.add(carol)

        assertFailsWith<PeerNotConnected>(
            "sendTo consults _peers, so a peer smuggled in after construction would be accepted and " +
                "recorded in `directed` — a delivered frame to a peer that was never admitted",
        ) { seam.sendTo(carol, byteArrayOf(1)) }
    }

    /** Control for door 2: taking a copy must not discard what the caller actually passed. */
    @Test
    fun aRosterPassedAsAMutableSetIsStillTheRosterTheSeamReports() {
        val seam = FakeSeam(selfId = alice, initialPeers = mutableSetOf(alice, bob, carol))

        assertEquals(
            setOf(alice, bob, carol),
            seam.peers.value,
            "`_peers = MutableStateFlow(setOf(selfId))` would satisfy both door-2 arms above while " +
                "silently throwing the caller's remotes away",
        )
    }
}
