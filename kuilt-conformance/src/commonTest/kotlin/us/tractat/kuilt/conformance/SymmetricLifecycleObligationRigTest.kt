package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The **positive control** for #2601's lifecycle/identity slice: proof that the joiner half each of
 * seven [SeamConformanceSuite] obligations gained can actually go red, and that it reds on the arm
 * it names rather than somewhere else in the same obligation.
 *
 * `Seam` is *one peer's symmetric view of a session*, but a role-split fabric ships two different
 * `Seam` implementations behind one harness — websocket hosts a `MeshSeam` and joins a `LinkSeam` —
 * so an obligation asserted on `host` alone proves at most half of what the harness under test
 * ships, and the other half is what a joining phone runs. Seven rows were host-only; each is now
 * asserted on both ends, and each has a pair below.
 *
 * ## The shape, copied deliberately from [JoinerRosterObligationRigTest]
 *
 * Every row gets **two** tests: a broken harness that must red, and a **control arm** on the same
 * harness with the break removed. The control is what separates "the assertion caught the defect"
 * from "this rig was red for some unrelated reason" — without it, a rig that failed to connect at
 * all would look like a successful demonstration.
 *
 * The broken end is always the **joiner**, and always by *decorating* the reference seam rather than
 * hand-writing a fabric: the assertions under test read one property each, so a decorator is a
 * faithful stimulus, and a hand-written fabric would add surface that could itself be wrong. Each
 * red assertion is checked by **message shape** — a rig that reddened on the host arm, or on a
 * precondition, would tick a "did it go red?" box while proving nothing.
 *
 * Where the obligation batches its arms through [assertAll], the count is asserted too
 * (`"1 assertion(s) failed"` / `"2 assertion(s) failed"`): a broader red means the rig broke the
 * pair rather than the one end it names. Where an obligation asserts sequentially, the first red
 * aborts it, so the message alone carries the shape.
 *
 * Driving the `internal` obligation bodies rather than the inherited `@Test` wrappers follows
 * [SeamConformanceUngatedCoreTest] and [JoinerRosterObligationRigTest]: it composes inside one
 * `runTest`, which is what wasmJs/JS needs (a bare nested `runTest` returns an un-awaited Promise),
 * and the harnesses are **anonymous** objects because a named concrete subclass would be collected
 * by the JUnit4 (Android) runner as its own test class.
 *
 * ## What this rig is not
 *
 * It demonstrates the *assertions*, not the *fixtures*. Whether a given in-tree harness could ever
 * reach these joiner-side failures is the separate question [JoinerRosterOrigin] exists for, and
 * these rows have their own per-row answer — recorded in each obligation's comment in
 * [SeamConformanceSuite], because it differs per obligation and a single blanket claim would be
 * false for most of them.
 */
class SymmetricLifecycleObligationRigTest {

    // ── (1) hostYieldsUsableSeamWithNonEmptySelfId ───────────────────────────

    @Test
    fun aJoinerWithAnEmptySelfIdFailsTheUsableSeamObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that mints an empty selfId must FAIL — the obligation is about a usable seam, " +
                "and a role-split fabric mints the joiner's id on a different code path than the host's",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWithEmptySelfId))
                .runHostYieldsUsableSeam(this)
        }
        assertRedOn("joiner selfId must be non-empty", failure)
    }

    @Test
    fun aJoinerWithANonEmptySelfIdPassesTheUsableSeamObligation(): TestResult = runTest {
        reference().runHostYieldsUsableSeam(this)
    }

    // ── (2) closeIsIdempotent ────────────────────────────────────────────────

    @Test
    fun aJoinerThatThrowsOnASecondCloseFailsTheIdempotencyObligation(): TestResult = runTest {
        // Not an AssertionError: a second close() that throws propagates its own exception, which is
        // precisely the failure mode — a teardown loop meets it as a crash, not as a soft report.
        val failure = assertFailsWith<IllegalStateException>(
            "a joiner throwing on a second close() must FAIL the idempotency obligation",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatThrowsOnSecondClose))
                .runCloseIsIdempotent(this)
        }
        assertTrue(
            SECOND_CLOSE_THREW in failure.message.orEmpty(),
            "the red must come from the JOINER's second close specifically; got: ${failure.message}",
        )
    }

    @Test
    fun anIdempotentJoinerPassesTheIdempotencyObligation(): TestResult = runTest {
        reference().runCloseIsIdempotent(this)
    }

    // ── (3) closeDrivesStateTornNormal ───────────────────────────────────────

    @Test
    fun aJoinerThatNeverLatchesTornFailsTheCloseDrivesTornObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose close() leaves state non-Torn must FAIL",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatNeverLatchesTorn))
                .runCloseDrivesStateTornNormal(this)
        }
        assertAll(
            { assertRedOn("joiner state must be Torn after close()", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatLatchesTornPassesTheCloseDrivesTornObligation(): TestResult = runTest {
        reference().runCloseDrivesStateTornNormal(this)
    }

    // ── (4) stateStaysTornAfterClose ─────────────────────────────────────────

    @Test
    fun aJoinerThatClobbersItsTornUnderChurnFailsTheStaysTornObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose post-close path overwrites the terminal Torn must FAIL — that is the " +
                "lost-terminal-transition class, and it wedges every state.first { it is Torn } waiter",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatClobbersTornOnSecondClose))
                .runStateStaysTornAfterClose(this)
        }
        assertAll(
            { assertRedOn("the JOINER's state must STAY Torn", failure) },
            // The host's terminal Torn is untouched, so exactly one of the two final arms may red.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatKeepsItsTornPassesTheStaysTornObligation(): TestResult = runTest {
        reference().runStateStaysTornAfterClose(this)
    }

    // ── (5) incomingCompletesWhenSeamCloses ──────────────────────────────────

    @Test
    fun aJoinerWhoseIncomingNeverCompletesFailsTheTerminationObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose incoming never terminates must FAIL — a consumer self-closing via " +
                "onCompletion never runs, and the leak is invisible from the host end",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseIncomingNeverCompletes))
                .runIncomingCompletesWhenSeamCloses(this)
        }
        assertAll(
            { assertRedOn("the JOINER's incoming must COMPLETE", failure) },
            // The joiner still reaches Torn, and the host is untouched: one arm of four.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerWhoseIncomingCompletesPassesTheTerminationObligation(): TestResult = runTest {
        reference().runIncomingCompletesWhenSeamCloses(this)
    }

    // ── (6) peersCollapseToSelfIdWhenTorn ────────────────────────────────────

    @Test
    fun aJoinerThatFreezesItsPreTearRosterFailsTheCollapseObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner still advertising a remote after its tear must FAIL — a decorator folding it " +
                "reads that peer as reachable when nothing can reach it",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWithAFrozenRoster))
                .runPeersCollapseToSelfIdWhenTorn(this)
        }
        assertAll(
            { assertRedOn("a Torn JOINER must advertise NO reachable remote peer", failure) },
            // selfId is still in the frozen roster, and the host collapses correctly: one arm of four.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatCollapsesItsRosterPassesTheCollapseObligation(): TestResult = runTest {
        reference().runPeersCollapseToSelfIdWhenTorn(this)
    }

    // ── (7) wovenSeamCapabilityIsHonest ──────────────────────────────────────

    @Test
    fun aJoinerFabricatingAnAvailabilityVerdictFailsTheHonestyObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner reporting a confident Available with no live path observer must FAIL",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamFabricatingAvailability))
                .runWovenSeamCapabilityIsHonest(this)
        }
        assertAll(
            { assertRedOn("the JOINER of a fabric with no live path observer", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerReportingUnknownPassesTheHonestyObligation(): TestResult = runTest {
        reference().runWovenSeamCapabilityIsHonest(this)
    }

    // ── the rig's own premise ────────────────────────────────────────────────

    /**
     * Guards what every arm above rests on: [DecoratingJoinerLoom] decorates the **joiner** and
     * leaves the host alone. If it decorated both, every red above would be ambiguous between "the
     * joiner arm fired" and "the rig broke the pair", and the arm-count assertions would be the only
     * thing standing between this file and a false demonstration.
     */
    @Test
    fun theRigDecoratesTheJoinerAndOnlyTheJoiner(): TestResult = runTest {
        val loom = DecoratingJoinerLoom(InMemoryLoom(), ::SeamWithEmptySelfId)
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("joiner"))
        try {
            assertAll(
                { assertTrue(joiner.selfId.value.isEmpty(), "the joiner must be decorated") },
                { assertTrue(host.selfId.value.isNotEmpty(), "the HOST must be left undecorated") },
            )
        } finally {
            host.close()
            joiner.close()
        }
    }

    // ── harnesses ────────────────────────────────────────────────────────────

    private fun reference(): SeamConformanceSuite = harnessOver(InMemoryLoom())

    private fun harnessOver(loom: Loom): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom

        // `reportsLiveCapability = false` on purpose: it selects the branch of
        // `wovenSeamCapabilityIsHonest` that SAMPLES (and so can red on a fabricated verdict). The
        // `true` branch AWAITS a non-Unknown verdict unbounded by design, and an InMemoryLoom seam
        // never publishes one — the rig would hang rather than assert.
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(reportsLiveCapability = false)

        override fun capabilityGaps(): Map<String, String> = mapOf("reportsLiveCapability" to GAP_URL)

        override fun joinerRosterOrigin(): JoinerRosterOrigin =
            JoinerRosterOrigin.FilledByConstruction("a rig over one InMemoryLoom (#2601 positive control)")
    }

    /**
     * Applies [decorate] to the seam handed back by `weave(Rendezvous.Existing)` — the joiner — and
     * nothing else.
     *
     * Implemented rather than delegated (`Loom by inner`) on purpose, the reason
     * [JoinerRosterObligationRigTest] gives: [Loom.host] and [Loom.join] are *default* members, so
     * delegation would forward them to the inner loom's own `weave` and route straight past this
     * override.
     */
    private class DecoratingJoinerLoom(
        private val inner: Loom,
        private val decorate: (Seam) -> Seam,
    ) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            return if (rendezvous is Rendezvous.Existing) decorate(seam) else seam
        }

        override fun capability(): TransportCapability = inner.capability()
    }

    // ── the broken joiners, one per row ──────────────────────────────────────

    /**
     * A joiner minting an empty [Seam.selfId]. `peers` is overridden to match, so the live-seam
     * monitor in `connectedPair` (`selfId ∈ peers`) stays satisfied and the only thing that can red
     * is the obligation under test.
     */
    private class SeamWithEmptySelfId(delegate: Seam) : Seam by delegate {
        override val selfId: PeerId = PeerId("")
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(PeerId("")))
    }

    /** A joiner whose `close()` is not idempotent — the second call throws. */
    private class SeamThatThrowsOnSecondClose(private val delegate: Seam) : Seam by delegate {
        private var closes = 0
        override suspend fun close(reason: CloseReason) {
            closes++
            check(closes == 1) { SECOND_CLOSE_THREW }
            delegate.close(reason)
        }
    }

    /** A joiner that closes its transport but never latches [SeamState.Torn]. */
    private class SeamThatNeverLatchesTorn(delegate: Seam) : Seam by delegate {
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    }

    /**
     * A joiner whose *second* close overwrites the terminal `Torn` with a stale non-terminal value —
     * the multi-writer clobber `stateStaysTornAfterClose` exists to keep dead, in the one shape a
     * deterministic rig can reach: an idempotent-close path that re-runs the state write.
     */
    private class SeamThatClobbersTornOnSecondClose(private val delegate: Seam) : Seam by delegate {
        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = _state
        private var closes = 0
        override suspend fun close(reason: CloseReason) {
            closes++
            delegate.close(reason)
            _state.value = if (closes == 1) SeamState.Torn(CloseReason.Normal) else SeamState.Weaving
        }
    }

    /** A joiner whose `incoming` relays the real flow and then never terminates. */
    private class SeamWhoseIncomingNeverCompletes(private val delegate: Seam) : Seam by delegate {
        override val incoming: Flow<Swatch> = flow {
            delegate.incoming.collect { emit(it) }
            awaitCancellation()
        }
    }

    /**
     * A joiner advertising a remote that survives its own tear — a frozen pre-tear roster. `selfId`
     * stays in it, so the *second* arm of the collapse obligation still passes and the red is
     * attributable to the first.
     */
    private class SeamWithAFrozenRoster(delegate: Seam) : Seam by delegate {
        override val peers: StateFlow<Set<PeerId>> =
            MutableStateFlow(setOf(delegate.selfId, PeerId("ghost-remote-that-never-leaves")))
    }

    /** A joiner with no live path observer reporting a confident verdict anyway. */
    private class SeamFabricatingAvailability(delegate: Seam) : Seam by delegate {
        override val capability: StateFlow<TransportCapability> =
            MutableStateFlow(TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available))
    }

    // ── shared assertions ────────────────────────────────────────────────────

    private fun assertRedOn(fragment: String, failure: Throwable) {
        assertTrue(
            fragment in failure.message.orEmpty(),
            "the red must come from the JOINER arm specifically (looking for \"$fragment\"); " +
                "got: ${failure.message}",
        )
    }

    private fun assertArmCount(expected: Int, failure: Throwable) {
        assertTrue(
            "$expected assertion(s) failed" in failure.message.orEmpty(),
            "exactly $expected arm(s) may red — a broader red means the rig broke the pair rather " +
                "than the one end it names; got: ${failure.message}",
        )
    }

    private companion object {
        private const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/2601"
        private const val SECOND_CLOSE_THREW = "rig: this joiner's second close() throws"
    }
}
