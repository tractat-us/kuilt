package us.tractat.kuilt.conformance

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
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
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The **positive control** for #2601's lifecycle/identity slice: proof that the joiner half each of
 * four [SeamConformanceSuite] obligations gained can actually go red, and that it reds on the arm
 * it names rather than somewhere else in the same obligation.
 *
 * `Seam` is *one peer's symmetric view of a session*, but a role-split fabric ships two different
 * `Seam` implementations behind one harness — websocket hosts a `MeshSeam` and joins a `LinkSeam` —
 * so an obligation asserted on `host` alone proves at most half of what the harness under test
 * ships, and the other half is what a joining phone runs.
 *
 * ## Four of the slice's seven rows, and the other three are not forgotten
 *
 * `closeDrivesStateTornNormal`, `stateStaysTornAfterClose` and `peersCollapseToSelfIdWhenTorn` are
 * **held back on #2372**, not skipped: their joiner arms were written, run, and red on a real
 * in-tree harness. `MuxServerLoomConformanceTest`'s joiner is a `NamedMux` channel view whose
 * `close()` drains its own spool while `state` and `peers` keep delegating to a live base, so it
 * never reaches `Torn` and a *closed* view still advertises `PeerId(server)`. One value, read from
 * three places. Each of those obligations carries the measurement and the exact assertion to
 * restore; a rig pair belongs beside each of them, and lands with them. Adding a control here for
 * an assertion that is not in the suite would assert nothing.
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
        assertAll(
            { assertRedOn("joiner selfId must be non-empty", failure) },
            { assertArmCount(1, failure) },
        )
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

    // ── (3) incomingCompletesWhenSeamCloses ──────────────────────────────────

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
            // The host is untouched: one arm of three.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerWhoseIncomingCompletesPassesTheTerminationObligation(): TestResult = runTest {
        reference().runIncomingCompletesWhenSeamCloses(this)
    }

    // ── (4) wovenSeamCapabilityIsHonest ──────────────────────────────────────

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

    /** A joiner whose `incoming` relays the real flow and then never terminates. */
    private class SeamWhoseIncomingNeverCompletes(private val delegate: Seam) : Seam by delegate {
        override val incoming: Flow<Swatch> = flow {
            delegate.incoming.collect { emit(it) }
            awaitCancellation()
        }
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
