package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The **positive control** for #2601's refusal slice: proof that the joiner half each of four
 * [SeamConformanceSuite] obligations gained can actually go red, and that it reds on the arm it
 * names rather than somewhere else in the same obligation.
 *
 * `Seam` is *one peer's symmetric view of a session*, but a role-split fabric ships two different
 * `Seam` implementations behind one harness — websocket hosts a `MeshSeam` and joins a `LinkSeam` —
 * so an obligation asserted on `host` alone proves at most half of what the harness under test
 * ships, and the other half is what a joining phone runs.
 *
 * ## Four rows, and why they are one slice
 *
 * `sendToAbsentPeerThrowsPeerNotConnected`, `sendOnTornSeamThrows`,
 * `payloadOfExactlyTheBudgetIsCarried` and `overBudgetAddressedSendIsRefusedNotLeaked` all turn on a
 * send being **refused** — the delivery rows #2601's previous slice took assert what a frame that
 * *does* cross carries. That is a different mechanism and a different vacuity question, and the
 * shared answer is what makes them one PR: **no fixture can supply a refusal.** A shared registry
 * can hand a joiner the host's id ([JoinerRosterOrigin] is the declaration for exactly that), and
 * on a shared-registry harness it even supplies the roster *collapse* a torn seam needs — but there
 * is nothing a harness can seed that makes a guard fire. Every one of these arms is the joiner's own
 * `check`/`require`/`oversizeOrNull`, or the absence of it.
 *
 * The fifth remaining row, `selfDialIsRejected`, is not here: its gate is
 * [SeamConformanceSuite.injectSelfDial], and asserting it on the joiner was a signature change, so it
 * landed on its own with its own control in [SymmetricSelfDialObligationRigTest]. That hook now takes
 * both ends, which closes #2601's list — this paragraph is kept because it is the reason this rig
 * covers four rows and not five, not because the row is still outstanding.
 *
 * ## Why the payload rows need a budgeted harness
 *
 * The other rigs run over a bare [InMemoryLoom]. The two payload rows are **value-selected** on
 * `Seam.maxPayloadBytes`, and `InMemoryLoom` publishes `null` — so over the reference pair they
 * assert nothing at all, and a rig built on it would be the "did it go red?" box ticked by a body
 * that never executed. [BudgetedLoom] gives *both* ends a published, enforced ceiling, which is the
 * minimum harness at which these arms exist; the joiner is then broken on top of it.
 *
 * ## The shape, copied deliberately from [SymmetricDeliveryObligationRigTest]
 *
 * Every row gets a broken harness that must red and a **control arm** on the same harness with the
 * break removed. The control is what separates "the assertion caught the defect" from "this rig was
 * red for some unrelated reason" — without it, a rig that failed to connect at all would look like a
 * successful demonstration.
 *
 * The broken end is always the **joiner**, and always by *decorating* the reference seam
 * ([DecoratingJoinerLoom]) rather than hand-writing a fabric: a decorator is a faithful stimulus for
 * "this end's guard is missing", and a hand-written fabric would add surface that could itself be
 * wrong. Each red is checked by **message shape** and by **arm count** — a rig that reddened on the
 * host arm, or on a precondition, would tick a box while proving nothing. Where two arms name one
 * defect the count says two, and the reason is stated at that test rather than rounded off.
 *
 * Driving the `internal` obligation bodies rather than the inherited `@Test` wrappers follows
 * [SeamConformanceUngatedCoreTest] and [JoinerRosterObligationRigTest]: it composes inside one
 * `runTest`, which is what wasmJs/JS needs (a bare nested `runTest` returns an un-awaited Promise),
 * and the harnesses are **anonymous** objects because a named concrete subclass would be collected
 * by the JUnit4 (Android) runner as its own test class.
 *
 * ## What this rig is not
 *
 * It demonstrates the *assertions*, not the *fixtures*. Whether a given in-tree harness reaches
 * these joiner-side failures is the separate question each obligation's own comment answers — and
 * for the two payload rows the answer was **measured** rather than argued, by reversing their gate
 * and reading which harnesses red.
 */
class SymmetricRefusalObligationRigTest {

    // ── (1) sendToAbsentPeerThrowsPeerNotConnected ───────────────────────────

    /**
     * The shipped counterexample, reconstructed: a 2-peer link's wire has exactly one addressee, so
     * an unguarded joiner resolves *any* id to the remote and reports success.
     * `sendToSelfIsRefused`'s comment records `LinkSeam` and `WebRTCPeerLink` doing precisely this
     * before #2428 — and `LinkSeam` is the joiner on every role-split harness in the tree.
     */
    @Test
    fun aJoinerThatMisdeliversAnUnaddressableSendFailsTheAbsentPeerObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that delivers a frame addressed to nobody must FAIL — reporting success is " +
                "the misdelivery this obligation forbids",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatSendsAnyAddressToTheRemote))
                .runSendToAbsentPeerThrows(this)
        }
        assertAll(
            { assertRedOn("must throw PeerNotConnected on the JOINER too", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatRefusesAnUnaddressableSendPassesTheAbsentPeerObligation(): TestResult = runTest {
        reference().runSendToAbsentPeerThrows(this)
    }

    // ── (2) sendOnTornSeamThrows ─────────────────────────────────────────────

    /** The warn-drop #1390 found on two fabrics, moved to the end no harness had ever asserted. */
    @Test
    fun aJoinerThatWarnDropsOnATornSeamFailsTheTornSendObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose broadcast silently returns on a Torn seam must FAIL — swallowing the " +
                "send tells the joining device every frame after the tear was delivered",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatWarnDropsWhenTorn))
                .runSendOnTornSeamThrows(this)
        }
        assertAll(
            { assertRedOn("broadcast on a Torn JOINER must throw too", failure) },
            { assertArmCount(1, failure) },
        )
    }

    /**
     * The #2448 clause, on the joiner. `PeerNotConnected` **is** an `IllegalStateException`, so the
     * type arm above it stays green and exactly one arm may move — which is the whole reason that
     * clause exists as its own assertion rather than as a stronger type on the first one.
     */
    @Test
    fun aJoinerThatBlamesTheHostForItsOwnTearFailsTheTornSendObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that answers a send on its own dead seam with PeerNotConnected must FAIL — " +
                "that is a claim about the HOST, and a caller who believes it retries against a corpse",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatBlamesTheAddresseeWhenTorn))
                .runSendOnTornSeamThrows(this)
        }
        assertAll(
            { assertRedOn("must report the TEAR, not blame the host", failure) },
            { assertArmCount(1, failure) },
        )
    }

    /**
     * The plain absence of the guard: the send just completes. The identity arm below it stays green
     * (`null` is not a `PeerNotConnected`), so this pins the **type** arm on its own — an arm that
     * would otherwise be a GREEN mutation row, i.e. a test nothing proves.
     */
    @Test
    fun aJoinerThatCompletesADirectedSendOnATornSeamFailsTheTornSendObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose directed send completes on a Torn seam must FAIL",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatCompletesEverySendToWhenTorn))
                .runSendOnTornSeamThrows(this)
        }
        assertAll(
            { assertRedOn("sendTo on a Torn JOINER must throw too", failure) },
            { assertArmCount(1, failure) },
        )
    }

    /**
     * The row's joiner **precondition**. Asking "does a Torn seam refuse?" of a seam that never
     * latched Torn would report `closeDrivesStateTornNormal`'s defect under this row's name, so the
     * precondition exists — and an unpinned precondition is exactly what #2669 refused to leave as a
     * GREEN mutation row.
     */
    @Test
    fun aJoinerThatNeverLatchesTornFailsTheTornSendPrecondition(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that never latches Torn must fail this row's PRECONDITION, not its obligation",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseStateNeverTears))
                .runSendOnTornSeamThrows(this)
        }
        assertAll(
            { assertRedOn("must latch Torn before 'a Torn seam refuses' means anything", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatRefusesEverySendOnATornSeamPassesTheTornSendObligation(): TestResult = runTest {
        reference().runSendOnTornSeamThrows(this)
    }

    /**
     * The gate is inside the body, so a fabric declaring `throwsOnSendToTorn = false` must run
     * **nothing** — including the joiner arms this slice added. Without this, a future edit hoisting
     * the gate back into the `@Test` wrapper would leave the body running against a fabric that had
     * opted out, and the two rig arms above would still be green.
     */
    @Test
    fun theTornSendObligationIsGatedInsideItsBody(): TestResult = runTest {
        val loom = DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatWarnDropsWhenTorn)
        object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities =
                SeamCapabilities.FULL.copy(throwsOnSendToTorn = false)

            override fun capabilityGaps(): Map<String, String> = mapOf("throwsOnSendToTorn" to GAP_URL)
            override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
        }.runSendOnTornSeamThrows(this)
    }

    // ── (3) payloadOfExactlyTheBudgetIsCarried ───────────────────────────────

    /**
     * The precise defect the last-byte arm was written for: length preserved, content lost. The size
     * arm cannot see it — which is why the arm exists, and why this rig asserts a count of **one**.
     */
    @Test
    fun aJoinerThatZeroPadsItsAtBudgetFrameFailsOnlyTheLastByteArm(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose at-budget broadcast arrives the right length carrying the wrong bytes " +
                "must FAIL",
        ) {
            budgetedHarnessBrokenBy(::SeamThatZeroPadsEveryBroadcast)
                .runPayloadOfExactlyTheBudgetIsCarried(this)
        }
        assertAll(
            { assertRedOn("must keep its last byte too", failure) },
            { assertArmCount(1, failure) },
        )
    }

    /**
     * Truncation reds **two** arms — the size and the last byte — and that is correct rather than
     * sloppy: a frame that arrived short is both facts at once, and reporting only one would cost a
     * reader the other half of the diagnosis. The count is asserted at two so a *broader* red still
     * fails this rig.
     */
    @Test
    fun aJoinerThatTruncatesItsAtBudgetFrameFailsTheSizeAndLastByteArms(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose at-budget broadcast loses a byte must FAIL — the number it published is " +
                "a promise about its own wire",
        ) {
            budgetedHarnessBrokenBy(::SeamThatTruncatesEveryBroadcast)
                .runPayloadOfExactlyTheBudgetIsCarried(this)
        }
        assertAll(
            { assertRedOn("must cross whole to the host too", failure) },
            { assertRedOn("must keep its last byte too", failure) },
            { assertArmCount(2, failure) },
        )
    }

    @Test
    fun anIntactJoinerPassesTheAtBudgetObligation(): TestResult = runTest {
        budgetedReference().runPayloadOfExactlyTheBudgetIsCarried(this)
    }

    /**
     * The row's **honest limit**, demonstrated rather than claimed: the joiner phase is gated on the
     * joiner's own `maxPayloadBytes`, so a joiner that publishes nothing runs no joiner arm and a
     * **broken** one passes. This harness's joiner both publishes `null` and zero-pads every frame,
     * i.e. it would fail [aJoinerThatZeroPadsItsAtBudgetFrameFailsOnlyTheLastByteArm] outright, and
     * it is green here.
     *
     * That is the silent skip #2601 names as its own failure mode, so it is pinned rather than
     * argued — and its reachability was **measured**: a probe reversing the gate found no in-tree
     * harness whose host publishes a budget while its joiner does not. The row's KDoc carries the
     * measurement and why asserting symmetry here would be the wrong fix.
     */
    @Test
    fun aJoinerPublishingNoBudgetSkipsTheJoinerArmsSilently(): TestResult = runTest {
        budgetedHarnessBrokenBy(::SeamThatZeroPadsAndPublishesNoBudget)
            .runPayloadOfExactlyTheBudgetIsCarried(this)
    }

    // ── (4) overBudgetAddressedSendIsRefusedNotLeaked ────────────────────────

    /**
     * *Published but unenforced* — the failure this row exists to forbid. The send returns success
     * and the frame dies later, in a write loop that cannot tell an oversize frame from a dead wire
     * and tears the session down. Two arms move (no refusal to type, no budget to name), which is
     * one defect reported twice on purpose.
     */
    @Test
    fun aJoinerThatAcceptsAnOverBudgetSendFailsTheRefusalObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that accepts one byte over its own published budget must FAIL",
        ) {
            budgetedHarnessBrokenBy(::SeamThatAcceptsAnOverBudgetSend)
                .runOverBudgetAddressedSendIsRefused(this)
        }
        assertAll(
            { assertRedOn("with PayloadTooLarge too", failure) },
            { assertRedOn("must name the budget the JOINER publishes", failure) },
            { assertArmCount(2, failure) },
        )
    }

    /**
     * The arm that would otherwise be redundant with the type arm above it: a joiner that refuses
     * correctly but names somebody else's number. A caller on the joining device can only read its
     * own seam's `maxPayloadBytes`, so a refusal naming a different one sends it to re-chunk against
     * a limit that is not the one it hit.
     */
    @Test
    fun aJoinerWhoseRefusalNamesTheWrongBudgetFailsOnlyTheBudgetArm(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that refuses with PayloadTooLarge naming a budget it does not publish must FAIL",
        ) {
            budgetedHarnessBrokenBy(::SeamWhoseRefusalNamesTheWrongBudget)
                .runOverBudgetAddressedSendIsRefused(this)
        }
        assertAll(
            { assertRedOn("must name the budget the JOINER publishes", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun anEnforcingJoinerPassesTheOverBudgetRefusalObligation(): TestResult = runTest {
        budgetedReference().runOverBudgetAddressedSendIsRefused(this)
    }

    /**
     * The gate is inside the body, as for the torn-send row: a fabric that does not support directed
     * addressing must run nothing here, joiner arms included.
     */
    @Test
    fun theOverBudgetRefusalObligationIsGatedInsideItsBody(): TestResult = runTest {
        val loom = DecoratingJoinerLoom(BudgetedLoom(InMemoryLoom()), ::SeamThatAcceptsAnOverBudgetSend)
        object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(supportsSendTo = false)
            override fun capabilityGaps(): Map<String, String> = mapOf("supportsSendTo" to GAP_URL)
            override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
            override fun payloadBudgetGap(): String? = null
        }.runOverBudgetAddressedSendIsRefused(this)
    }

    // ── the rig's own premise ────────────────────────────────────────────────

    /**
     * Guards what the two payload rows above rest on: [BudgetedLoom] gives **both** ends the budget,
     * and [DecoratingJoinerLoom] breaks only the joiner. Asserted behaviourally — the host still
     * refuses an over-budget addressed send while the broken joiner accepts one — because the
     * decoration is a property of a send path rather than of a field a reader could compare.
     *
     * Without this a rig whose `BudgetedLoom` had somehow decorated the host instead would still
     * produce reds, on the wrong end, and every message-shape assertion above would be true for the
     * wrong reason.
     */
    @Test
    fun theBudgetedRigDecoratesTheJoinerAndOnlyTheJoiner(): TestResult = runTest {
        val loom = DecoratingJoinerLoom(BudgetedLoom(InMemoryLoom()), ::SeamThatAcceptsAnOverBudgetSend)
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("joiner"))
        try {
            // `runCatchingCancellable`, never a bare `runCatching`: the latter would swallow this
            // test's own cancellation and turn a structured-concurrency cancel into a Result.
            val hostRefusal =
                runCatchingCancellable { host.sendTo(joiner.selfId, ByteArray(BUDGET + 1)) }.exceptionOrNull()
            val joinerRefusal =
                runCatchingCancellable { joiner.sendTo(host.selfId, ByteArray(BUDGET + 1)) }.exceptionOrNull()
            assertAll(
                { assertEquals(BUDGET, host.maxPayloadBytes, "both ends must publish the rig's budget") },
                { assertEquals(BUDGET, joiner.maxPayloadBytes, "both ends must publish the rig's budget") },
                {
                    assertIs<PayloadTooLarge>(
                        hostRefusal,
                        "the HOST's guard must be left undecorated; got ${hostRefusal ?: "no exception"}",
                    )
                },
                { assertNull(joinerRefusal, "the JOINER must be the broken end; got $joinerRefusal") },
            )
        } finally {
            host.close()
            joiner.close()
        }
    }

    // ── harnesses ────────────────────────────────────────────────────────────

    private fun reference(): SeamConformanceSuite = harnessOver(InMemoryLoom())

    private fun budgetedReference(): SeamConformanceSuite = budgetedHarnessOver(BudgetedLoom(InMemoryLoom()))

    private fun budgetedHarnessBrokenBy(decorate: (Seam) -> Seam): SeamConformanceSuite =
        budgetedHarnessOver(DecoratingJoinerLoom(BudgetedLoom(InMemoryLoom()), decorate))

    private fun harnessOver(loom: Loom): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
    }

    /** As [harnessOver], plus the `payloadBudgetGap()` clearance a fabric that publishes a budget owes. */
    private fun budgetedHarnessOver(loom: Loom): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
        override fun payloadBudgetGap(): String? = null
    }

    // ── the budgeted reference the payload rows need ─────────────────────────

    /**
     * Gives **every** seam it weaves a published, enforced payload ceiling — the minimum harness at
     * which the two payload rows assert anything at all, since `InMemoryLoom` publishes `null`.
     *
     * Enforcement mirrors the contract rather than a particular fabric: `broadcast` is best-effort
     * and *drops* an over-budget payload, `sendTo` refuses it with [PayloadTooLarge]. A rig whose
     * reference did not enforce would make every control arm above green by construction.
     */
    private class BudgetedLoom(private val inner: Loom) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = BudgetedSeam(inner.weave(rendezvous))
        override fun capability(): TransportCapability = inner.capability()
    }

    private class BudgetedSeam(private val delegate: Seam) : Seam by delegate {
        override val maxPayloadBytes: Int get() = BUDGET

        override suspend fun broadcast(payload: ByteArray) {
            if (payload.size > BUDGET) return
            delegate.broadcast(payload)
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (payload.size > BUDGET) throw PayloadTooLarge(payload.size, BUDGET, 0)
            delegate.sendTo(peer, payload)
        }
    }

    // ── the broken joiners, one per defect ───────────────────────────────────

    /** A 2-peer link with no roster guard: every address resolves to the one remote. */
    private class SeamThatSendsAnyAddressToTheRemote(private val delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = delegate.broadcast(payload)
    }

    /** A joiner whose `broadcast` returns success on a dead seam — the shipped shape of a warn-drop. */
    private class SeamThatWarnDropsWhenTorn(private val delegate: Seam) : Seam by delegate {
        override suspend fun broadcast(payload: ByteArray) {
            if (delegate.state.value is SeamState.Torn) return
            delegate.broadcast(payload)
        }
    }

    /** A joiner whose roster lookup runs ahead of its own tear check, so it blames the addressee. */
    private class SeamThatBlamesTheAddresseeWhenTorn(private val delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (delegate.state.value is SeamState.Torn) throw PeerNotConnected(peer)
            delegate.sendTo(peer, payload)
        }
    }

    /** Right length, wrong bytes: the truncate-and-zero-pad the last-byte arm exists to catch. */
    private class SeamThatZeroPadsEveryBroadcast(private val delegate: Seam) : Seam by delegate {
        override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(ByteArray(payload.size))
    }

    /** A joiner whose directed send completes on a dead seam — the guard simply absent. */
    private class SeamThatCompletesEverySendToWhenTorn(private val delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (delegate.state.value is SeamState.Torn) return
            delegate.sendTo(peer, payload)
        }
    }

    /** Broken exactly as [SeamThatZeroPadsEveryBroadcast] is, and invisible because it names no budget. */
    private class SeamThatZeroPadsAndPublishesNoBudget(private val delegate: Seam) : Seam by delegate {
        override val maxPayloadBytes: Int? get() = null
        override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(ByteArray(payload.size))
    }

    /** A joiner whose wire really takes one byte less than the number it publishes. */
    private class SeamThatTruncatesEveryBroadcast(private val delegate: Seam) : Seam by delegate {
        override suspend fun broadcast(payload: ByteArray) =
            delegate.broadcast(payload.copyOf(maxOf(0, payload.size - 1)))
    }

    /** Published but unenforced: the over-budget send is accepted and lost. */
    private class SeamThatAcceptsAnOverBudgetSend(private val delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (payload.size > BUDGET) return
            delegate.sendTo(peer, payload)
        }
    }

    /** Refuses correctly, names somebody else's number. */
    private class SeamWhoseRefusalNamesTheWrongBudget(private val delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (payload.size > BUDGET) throw PayloadTooLarge(payload.size, BUDGET + WRONG_BUDGET_SKEW, 0)
            delegate.sendTo(peer, payload)
        }
    }

    private companion object {
        private const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/2601"

        /**
         * The rig's published ceiling. Small, and chosen so the at-budget payload's **last** byte is
         * non-zero under `SeamConformanceSuite`'s non-uniform fill (`(BUDGET - 1) % 251 != 0`) —
         * otherwise a zero-padding joiner would produce the right byte by accident and
         * [aJoinerThatZeroPadsItsAtBudgetFrameFailsOnlyTheLastByteArm] would be green for the wrong
         * reason. Pinned by that test's own red rather than left as a comment.
         */
        private const val BUDGET = 64

        /** How far a wrong-number refusal misses by. Any non-zero value; one is the smallest lie. */
        private const val WRONG_BUDGET_SKEW = 1

        private val RIG_ROSTER = JoinerRosterOrigin.FilledByConstruction(
            "a rig over one InMemoryLoom (#2601 positive control): the shared registry fills both " +
                "rosters at weave time, which is fine here — these rows are about whether a send is " +
                "REFUSED, and no fixture can supply a refusal",
        )
    }
}
