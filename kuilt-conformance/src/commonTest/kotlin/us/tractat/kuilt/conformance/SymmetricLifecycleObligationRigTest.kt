package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
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
 * ships, and the other half is what a joining phone runs.
 *
 * ## Four rows, then the three that were held back on #2372
 *
 * `closeDrivesStateTornNormal`, `stateStaysTornAfterClose` and `peersCollapseToSelfIdWhenTorn` were
 * **held back**, not skipped: their joiner arms were written, run, and red on a real in-tree
 * harness. `MuxServerLoomConformanceTest`'s joiner is a `NamedMux` channel view, and
 * `MuxBase.ChannelView` used to keep `state` and `peers` delegating to a live base, so it never
 * reached `Torn` and a *closed* view still advertised `PeerId(server)`. One value, read from three
 * places. #2372 fixed it — a channel view now owns both — so the hold is discharged, the three arms
 * are in the suite, and their controls join the four here.
 *
 * They are **terminal-state** rows rather than identity ones, and that changes what a broken joiner
 * has to be. The first four are broken by a decorator that lies about a *static* property (an empty
 * `selfId`, a fabricated availability); these three are broken by a decorator that lies about a
 * value **across a transition** — a `state` that never tears, a terminal `Torn` a second `close()`
 * clobbers, a roster frozen at its pre-tear contents. So each of the three defects is expressed as
 * *when* the decorator diverges, not as *what* it returns, and each control arm is the same
 * decorator with the divergence removed.
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

    // ── (5) closeDrivesStateTornNormal ───────────────────────────────────────

    @Test
    fun aJoinerWhoseStateNeverTearsFailsTheTornLatchObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose close() never latches Torn must FAIL — every `state.first { it is Torn }` " +
                "waiter on the joining device wedges while the host looks perfectly healthy",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseStateNeverTears))
                .runCloseDrivesStateTornNormal(this)
        }
        assertAll(
            { assertRedOn("the JOINER's state must be Torn after close()", failure) },
            // The host latches its own Torn: one arm of two.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatLatchesTornPassesTheTornLatchObligation(): TestResult = runTest {
        reference().runCloseDrivesStateTornNormal(this)
    }

    // ── (6) stateStaysTornAfterClose ─────────────────────────────────────────

    /**
     * The clobber is bound to the joiner's **second** `close()` on purpose: the obligation samples
     * the terminal value after the first one and re-reads after the churn, so a decorator that
     * simply never tears would red the *precondition* instead and prove nothing about this row. What
     * this rig has to reproduce is a terminal `Torn` that was reached and then overwritten.
     */
    @Test
    fun aJoinerThatClobbersItsTerminalTornFailsTheStaysTornObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose second close() overwrites its terminal Torn must FAIL — that is the " +
                "lost-terminal-transition class this obligation exists to keep dead",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatClobbersTornOnSecondClose))
                .runStateStaysTornAfterClose(this)
        }
        // Sequential, not batched: the joiner half mirrors the host half, so the first red aborts
        // and the message alone carries the shape.
        assertRedOn("the JOINER's state must STAY Torn after post-close churn too", failure)
    }

    @Test
    fun aJoinerThatKeepsItsTerminalTornPassesTheStaysTornObligation(): TestResult = runTest {
        reference().runStateStaysTornAfterClose(this)
    }

    /**
     * The row's other joiner arm. A seam that stays `Torn` but reports a *different* reason under
     * churn has still re-run its terminal write with a stale value — the same defect, one field
     * over — and without this arm that `assertEquals` would be a GREEN mutation row, i.e. an
     * assertion nothing proves.
     */
    @Test
    fun aJoinerThatRewritesItsTornReasonFailsTheStaysTornObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that keeps Torn but rewrites its reason under churn must FAIL",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatRewritesItsTornReasonOnSecondClose))
                .runStateStaysTornAfterClose(this)
        }
        assertRedOn("the JOINER's terminal Torn reason must not change under churn either", failure)
    }

    /**
     * The row's joiner **precondition**. Asserting "it stayed Torn" against a seam that never
     * latched Torn would report `closeDrivesStateTornNormal`'s defect under this row's name, so the
     * precondition exists — and an unpinned precondition is exactly what #2669 refused to leave as a
     * GREEN mutation row.
     */
    @Test
    fun aJoinerThatNeverLatchesTornFailsTheStaysTornPrecondition(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that never latches Torn must fail this row's PRECONDITION, not its obligation",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseStateNeverTears))
                .runStateStaysTornAfterClose(this)
        }
        assertRedOn("precondition: the JOINER's close() must latch Torn before", failure)
    }

    // ── (7) peersCollapseToSelfIdWhenTorn ────────────────────────────────────

    @Test
    fun aJoinerStillAdvertisingARemotePeerFailsTheCollapseObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner still advertising a remote peer once Torn must FAIL — a CompositeSeam on the " +
                "JOINING device folds that peer as reachable when only sendTo can disprove it",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatKeepsAdvertisingARemotePeerOnceTorn))
                .runPeersCollapseToSelfIdWhenTorn(this)
        }
        assertAll(
            { assertRedOn("the JOINER must advertise NO reachable remote peer once Torn", failure) },
            // Two host arms and two joiner arms; the phantom is added ALONGSIDE selfId rather than
            // replacing it, so the joiner's collapsed-too-far arm must stay green. One of four.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerThatCollapsesItsRosterPassesTheCollapseObligation(): TestResult = runTest {
        reference().runPeersCollapseToSelfIdWhenTorn(this)
    }

    /**
     * The other half of the collapse: a joiner that drops its **own** `selfId` has collapsed too
     * far, and the two deviations have different causes and different fixes — which is why the
     * obligation asserts them separately rather than as one set equality. Without this arm the
     * `selfId ∈ peers` assertion would be a GREEN mutation row, i.e. an unproven assertion.
     *
     * Both roster decorators lie on `peers.value` and leave `peers.collect` delegating to the real
     * flow — stated because it is a real limit on what they demonstrate, not a detail. It keeps
     * `connectedPair`'s continuous `selfId ∈ peers` monitor reading the honest values, so the red is
     * attributable to the one named assertion instead of racing a monitor that would reach the same
     * defect an instant earlier and report it under a different message. A genuinely broken seam
     * would of course be wrong on both surfaces; what these rigs prove is that the obligation's own
     * assertion catches it, not that it is the only thing that would.
     */
    @Test
    fun aJoinerThatDropsItsOwnSelfIdFailsTheCollapseObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose torn roster is empty rather than { selfId } must FAIL — peers always " +
                "includes this peer's own id",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseRosterCollapsesTooFar))
                .runPeersCollapseToSelfIdWhenTorn(this)
        }
        assertAll(
            { assertRedOn("the JOINER's collapsed roster is { selfId }, not empty", failure) },
            { assertArmCount(1, failure) },
        )
    }

    /**
     * The collapse row's two joiner **preconditions**, one test each. Both exist to stop an arm
     * being green by absence — a joiner that never named a remote peer has nothing to collapse, and
     * one that never latched `Torn` is not under this obligation at all — and an unpinned
     * precondition is the GREEN mutation row #2669 refused to leave behind.
     */
    @Test
    fun aJoinerThatNeverNamedTheHostFailsTheCollapsePrecondition(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose roster never named the host must fail this row's roster PRECONDITION",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseRosterNeverNamesTheHost))
                .runPeersCollapseToSelfIdWhenTorn(this)
        }
        assertRedOn("precondition: the JOINER must name a remote peer before the tear too", failure)
    }

    @Test
    fun aJoinerThatNeverLatchesTornFailsTheCollapsePrecondition(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that never latches Torn must fail this row's Torn PRECONDITION",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseStateNeverTears))
                .runPeersCollapseToSelfIdWhenTorn(this)
        }
        assertRedOn("precondition: the JOINER's close() must latch Torn", failure)
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

    // [DecoratingJoinerLoom] — the joiner-only decoration every arm above rests on — is shared with
    // [SymmetricDeliveryObligationRigTest]; see `JoinerRigSupport.kt`.

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

    /**
     * A joiner whose `state` never leaves [SeamState.Woven] — the shape `MuxBase.ChannelView` had
     * before #2372, where `state` delegated to a base connection that is still alive.
     *
     * The real close still runs underneath, so the joiner genuinely tears; only what it *reports*
     * is wrong, which is the defect a consumer meets.
     */
    private class SeamWhoseStateNeverTears(delegate: Seam) : Seam by delegate {
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    }

    /**
     * A joiner that reaches [SeamState.Torn] and then overwrites it on a **second** `close()` — the
     * multi-writer clobber `stateStaysTornAfterClose` exists to keep dead.
     *
     * `state` mirrors the delegate until the clobber, so the obligation's precondition (the first
     * close latched Torn) is honestly satisfied and only the re-read after the churn diverges.
     */
    private class SeamThatClobbersTornOnSecondClose(private val delegate: Seam) : Seam by delegate {
        private var closes = 0
        private val clobbered = MutableStateFlow<SeamState>(SeamState.Woven)
        private var clobbering = false
        override val state: StateFlow<SeamState>
            get() = if (clobbering) clobbered else delegate.state

        override suspend fun close(reason: CloseReason) {
            closes++
            delegate.close(reason)
            if (closes >= 2) clobbering = true
        }
    }

    /**
     * A joiner that reaches [SeamState.Torn] and keeps it, but rewrites its **reason** on a second
     * `close()` — the same stale re-write as [SeamThatClobbersTornOnSecondClose], one field over.
     */
    private class SeamThatRewritesItsTornReasonOnSecondClose(private val delegate: Seam) : Seam by delegate {
        private var closes = 0
        private val rewritten = MutableStateFlow<SeamState>(SeamState.Torn(CloseReason.Unreachable))
        private var rewriting = false
        override val state: StateFlow<SeamState>
            get() = if (rewriting) rewritten else delegate.state

        override suspend fun close(reason: CloseReason) {
            closes++
            delegate.close(reason)
            if (closes >= 2) rewriting = true
        }
    }

    /**
     * A joiner whose roster never names the host — the state the collapse row's own precondition
     * exists to refuse, since a joiner with nothing to lose satisfies the collapse by absence.
     */
    private class SeamWhoseRosterNeverNamesTheHost(delegate: Seam) : Seam by delegate {
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(delegate.selfId))
    }

    /**
     * A joiner that still advertises a remote peer once `Torn` — the shape `MuxBase.ChannelView` had
     * before #2372, where a closed view still advertised `PeerId(server)`.
     *
     * **It fabricates the symptom rather than replaying the mechanism, and the reason is a finding
     * about the reference harness rather than a convenience.** The obvious rig — freeze the roster at
     * whatever it held when `close()` was called — does **not** red here, and that was measured
     * before this shape was written. On `InMemoryLoom` the two ends share one registry, and the
     * obligation closes the **host** first: `host.close()` removes the host from the shared registry,
     * so the joiner's roster is already `{ joinerSelfId }` by the time the joiner tears. A freeze
     * taken at that instant captures an *already-collapsed* set and passes.
     *
     * That is not the rig failing to reproduce the defect; it is the collapse being supplied by the
     * fixture — the `JoinerRosterOrigin.FilledByConstruction` shape one level over, recorded on the
     * obligation itself. A phantom remote is independent of who departed first, so it isolates the
     * assertion, which is all a positive control is for. What it consequently does **not** prove is
     * that any in-tree harness can reach this failure by its own mechanism; the obligation's comment
     * says which fabric shapes can.
     */
    private class SeamThatKeepsAdvertisingARemotePeerOnceTorn(private val delegate: Seam) : Seam by delegate {
        private val overridden = OverridablePeers(delegate.peers)
        override val peers: StateFlow<Set<PeerId>> = overridden
        override suspend fun close(reason: CloseReason) {
            delegate.close(reason)
            overridden.overrideWith(delegate.peers.value + PHANTOM_REMOTE)
        }
    }

    /**
     * A joiner whose torn roster collapses to `emptySet()` rather than to `{ selfId }` — the *other*
     * deviation `peersCollapseToSelfIdWhenTorn` names, and the one a single set-equality assertion
     * would have folded into the first.
     */
    private class SeamWhoseRosterCollapsesTooFar(private val delegate: Seam) : Seam by delegate {
        private val overridden = OverridablePeers(delegate.peers)
        override val peers: StateFlow<Set<PeerId>> = overridden
        override suspend fun close(reason: CloseReason) {
            delegate.close(reason)
            overridden.overrideWith(emptySet())
        }
    }

    /**
     * A [StateFlow] that reports [overrideWith]'s value from `value` once set, and delegates
     * everything else — `collect` included — to the real flow. See
     * [aJoinerThatDropsItsOwnSelfIdFailsTheCollapseObligation] for why `collect` is deliberately
     * left honest.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class OverridablePeers(
        private val delegate: StateFlow<Set<PeerId>>,
    ) : StateFlow<Set<PeerId>> by delegate {
        private var override: Set<PeerId>? = null
        fun overrideWith(value: Set<PeerId>) {
            override = value
        }

        override val value: Set<PeerId> get() = override ?: delegate.value
        override val replayCache: List<Set<PeerId>> get() = listOf(value)
    }

    // `assertRedOn` / `assertArmCount` are shared with [SymmetricDeliveryObligationRigTest]; see
    // `JoinerRigSupport.kt` for what each one is guarding against.

    private companion object {
        private const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/2601"
        private const val SECOND_CLOSE_THREW = "rig: this joiner's second close() throws"

        /** The unreachable remote a torn joiner must not go on advertising. */
        private val PHANTOM_REMOTE = PeerId("rig-remote-the-torn-joiner-cannot-reach")
    }
}
