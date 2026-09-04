package us.tractat.kuilt.conformance

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **positive control** for #2568: proof that [ObligationDeclaration]'s two by-design arms can
 * actually go red, and that they go red on the thing they name.
 *
 * The hazard this whole vocabulary had to avoid is that an *"I cannot reach this state"* opt-out
 * moves the vacuity one level up, where it is harder to see: a [ObligationDeclaration.NotApplicable]
 * arm the suite merely **believed** would turn a visible tracked gap into an invisible self-certified
 * green — strictly worse than the state it replaced. So every arm is cross-checked against the
 * harness's own injection hook, and the two arms that can be refuted are refuted. A guard that has
 * never been seen to fail is indistinguishable from an assertion that cannot fire, which is why each
 * lie below is paired with a **control arm** — the same harness with the lie removed. Without the
 * control, a rig that simply failed to connect would look like a successful demonstration.
 *
 * Driving the `internal` body ([SeamConformanceSuite.runMidSessionDeathDeclarationIsHonest]) rather
 * than the inherited `@Test` wrapper follows [SeamConformanceUngatedCoreTest] and
 * [JoinerRosterObligationRigTest]: it composes inside one `runTest`, which is what wasmJs/JS needs (a
 * bare nested `runTest` returns an un-awaited Promise), and the harnesses are **anonymous** objects
 * because a named concrete subclass would be collected by the JUnit4 (Android) runner as its own test
 * class.
 *
 * ## The two fabrics, and why each is the right stimulus
 *
 *  - **A `peerMesh` over one [connectionPair]** — a genuine 2-peer link whose transport this rig
 *    holds both ends of. Dropping it tears BOTH seams, so this fabric *satisfies* the mid-session-death
 *    obligation. That makes it the harness on which both by-design arms are lies, and the one that
 *    stands in for the regression #2568 exists to prevent: the day `NwSeam` starts tearing on peer
 *    loss it looks exactly like this, and its `ContractDiffers` declaration must red.
 *  - **The reference [InMemoryLoom]** — one shared registry, no transport under the pair. A peer
 *    leaving shrinks the roster and leaves the survivor `Woven`, which is what makes
 *    [ObligationDeclaration.NotApplicable.NotConstructible] honest there. It is the control fabric.
 */
class ObligationDeclarationRigTest {

    // ── ContractDiffers: the arm must be DEMONSTRATED, not asserted ───────────

    @Test
    fun contractDiffersWithoutAnInjectionIsRefused(): TestResult = runTest {
        // The arm's whole strength is that the harness has to perform the event and be watched. A
        // harness that declares it while injecting nothing is exactly the self-certification the
        // vocabulary exists to refuse.
        val failure = assertFailsWith<AssertionError> {
            harness(InMemoryLoom(), CONTRACT_DIFFERS).runMidSessionDeathDeclarationIsHonest(this)
        }
        assertTrue(
            "must DEMONSTRATE" in failure.message.orEmpty(),
            "the red must name the missing demonstration, not some other arm; got: ${failure.message}",
        )
    }

    @Test
    fun contractDiffersOnAFabricThatDoesTearIsRefused(): TestResult = runTest {
        // THE #1513 REGRESSION GUARD. This harness injects a real transport death and its fabric
        // latches Torn on both ends — i.e. it satisfies the obligation. Declaring "my fabric answers
        // this differently by design" is then false, and the suite has to say so rather than take the
        // declaration's word for it. This is the shape a re-introduced tear-on-peer-loss in NwSeam
        // would take.
        val rig = TearingLinkRig()
        val failure = assertFailsWith<AssertionError> {
            tearingHarness(rig, CONTRACT_DIFFERS).runMidSessionDeathDeclarationIsHonest(this)
        }
        assertAll(
            {
                assertTrue(
                    "latched Torn" in failure.message.orEmpty(),
                    "the red must be the DEVIATION check — a harness whose fabric tore anyway; got: " +
                        "${failure.message}",
                )
            },
            {
                assertTrue(
                    rig.injected,
                    "rig precondition: the injection must actually have run, or the red above would be " +
                        "about a missing demonstration rather than a false one",
                )
            },
        )
    }

    @Test
    fun contractDiffersOnARecoverableFabricPasses(): TestResult = runTest {
        // Control arm: identical wiring and the identical real injection, over seams that treat the
        // drop as recoverable (NwSeam's #1513 behaviour). The arm passes, so the red above is the
        // assertion catching the lie rather than the rig being broken.
        recoverableHarness(TearingLinkRig(), CONTRACT_DIFFERS).runMidSessionDeathDeclarationIsHonest(this)
    }

    @Test
    fun aBlankContractDiffersReasonIsRefused(): TestResult = runTest {
        assertFailsWith<AssertionError>("a by-design arm must cost a sentence") {
            recoverableHarness(TearingLinkRig(), ObligationDeclaration.NotApplicable.ContractDiffers(" "))
                .runMidSessionDeathDeclarationIsHonest(this)
        }
    }

    // ── NotConstructible: the stated reason must not be cheaply refutable ─────

    @Test
    fun notConstructibleOnAHarnessWhoseDepartureTearsIsRefused(): TestResult = runTest {
        // NotConstructible's stated reason is always some form of "a peer going away does not tear the
        // survivor here, so there is no death to inject". On a real 2-peer link that is false, and the
        // suite refutes it by making the counterpart leave and watching the survivor latch Torn.
        val failure = assertFailsWith<AssertionError> {
            tearingHarness(TearingLinkRig(), NOT_CONSTRUCTIBLE, injects = false)
                .runMidSessionDeathDeclarationIsHonest(this)
        }
        assertTrue(
            "IS reachable here" in failure.message.orEmpty(),
            "the red must be the reachability refutation, not the prose toll; got: ${failure.message}",
        )
    }

    @Test
    fun notConstructibleOnASharedMeshPasses(): TestResult = runTest {
        // Control arm, and the shape all six in-process/hub harnesses in tree actually declare: one
        // shared registry, so the counterpart leaving drains the roster and the survivor stays Woven.
        harness(InMemoryLoom(), NOT_CONSTRUCTIBLE).runMidSessionDeathDeclarationIsHonest(this)
    }

    @Test
    fun notConstructibleWhoseDepartureStimulusIsANoOpIsRefused(): TestResult = runTest {
        // THE CASE THIS RIG WAS MISSING, and its absence is why a vacuous arm shipped to review
        // (#2568). The refutation concludes from an ABSENCE of a tear, so a departure that never
        // happens produces the identical green as a topology that genuinely survives one. That is not
        // hypothetical: MuxServerLoomConformanceTest's joiner is a NamedMux channel view whose close()
        // drains its own spool and departs nobody (#2372), so the default stimulus was a no-op there
        // and the arm was green by absence — inside the very PR built to stop arms being believed.
        val failure = assertFailsWith<AssertionError> {
            noOpDepartureHarness(NOT_CONSTRUCTIBLE).runMidSessionDeathDeclarationIsHonest(this)
        }
        assertTrue(
            "never landed" in failure.message.orEmpty(),
            "the red must name the un-landed stimulus, not the no-tear conclusion drawn from it; got: " +
                "${failure.message}",
        )
    }

    @Test
    fun aBlankNotConstructibleReasonIsRefused(): TestResult = runTest {
        assertFailsWith<AssertionError>("a by-design arm must cost a sentence") {
            harness(InMemoryLoom(), ObligationDeclaration.NotApplicable.NotConstructible("  "))
                .runMidSessionDeathDeclarationIsHonest(this)
        }
    }

    // ── Proven / Gap: the consistency half ───────────────────────────────────

    @Test
    fun provenWithoutAnInjectionIsRefused(): TestResult = runTest {
        // Proven claims the obligation RAN. With the hook at its default `false` it early-returned and
        // asserted nothing — the silent skip the whole mechanism exists to prevent.
        assertFailsWith<AssertionError> {
            harness(InMemoryLoom(), ObligationDeclaration.Proven).runMidSessionDeathDeclarationIsHonest(this)
        }
    }

    @Test
    fun provenWithAnInjectionPasses(): TestResult = runTest {
        tearingHarness(TearingLinkRig(), ObligationDeclaration.Proven).runMidSessionDeathDeclarationIsHonest(this)
    }

    @Test
    fun aGapDeclaredByAHarnessThatCanInjectIsRefused(): TestResult = runTest {
        // A harness holding a working injection has no gap: whatever it found, it is Proven or
        // ContractDiffers. Leaving a Gap there would keep an umbrella issue open forever on work that
        // is already done — the other half of #2568's complaint about an inventory that overstates
        // its own size.
        assertFailsWith<AssertionError> {
            tearingHarness(TearingLinkRig(), ObligationDeclaration.Gap(SOME_URL))
                .runMidSessionDeathDeclarationIsHonest(this)
        }
    }

    @Test
    fun aBlankGapUrlIsRefused(): TestResult = runTest {
        assertFailsWith<AssertionError>("a blank URL is not a declared gap") {
            harness(InMemoryLoom(), ObligationDeclaration.Gap(" ")).runMidSessionDeathDeclarationIsHonest(this)
        }
    }

    @Test
    fun aGapOnAHarnessThatCannotInjectPasses(): TestResult = runTest {
        // Control arm, and the inherited default every un-overridden harness in tree still takes.
        harness(InMemoryLoom(), ObligationDeclaration.Gap(SOME_URL)).runMidSessionDeathDeclarationIsHonest(this)
    }

    // ── the rig's own premises ───────────────────────────────────────────────

    @Test
    fun theTearingRigReallyTearsBothEnds(): TestResult = runTest {
        // Guards the premise both ContractDiffers reds rest on: if this fabric did NOT tear, the
        // "declared ContractDiffers but tore anyway" test would be green for the wrong reason.
        val rig = TearingLinkRig()
        val (hostLoom, joinerLoom) = rig.loomPair()
        coroutineScope {
            // CONCURRENTLY, as SeamConformanceSuite.connectedPair does: peerMesh runs its per-link
            // handshake inside weave(), so a sequential host() suspends forever waiting for a joiner
            // that has not started yet — which is a HANG, not a failure.
            val hostWeave = async { hostLoom.host(Pattern("host")) }
            val joinerWeave = async { joinerLoom.join(InMemoryTag("joiner")) }
            val host = hostWeave.await()
            val joiner = joinerWeave.await()
            try {
                rig.drop()
                val hostTorn = withTimeout(5.seconds) { host.state.first { it is SeamState.Torn } }
                val joinerTorn = withTimeout(5.seconds) { joiner.state.first { it is SeamState.Torn } }
                assertAll(
                    { assertTrue(hostTorn is SeamState.Torn, "the rig's host must latch Torn on a dropped link") },
                    { assertTrue(joinerTorn is SeamState.Torn, "the rig's joiner must latch Torn too") },
                )
            } finally {
                // Both seams own background read loops. Unlike every other test here this one weaves
                // OUTSIDE connectedPair, so nothing else will close them — and an unclosed peerMesh
                // spins runTest's terminal advance until the wedge backstop fires.
                host.close()
                joiner.close()
            }
        }
    }

    // ── harnesses ────────────────────────────────────────────────────────────

    /** A harness over [loom] with no injection hook — the "cannot inject" side of every pairing. */
    private fun harness(loom: Loom, declaration: ObligationDeclaration): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
            override fun capabilityGaps(): Map<String, String> = emptyMap()
            override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
            override fun midSessionDeathDeclaration(): ObligationDeclaration = declaration
        }

    /**
     * The reference mesh — on which [ObligationDeclaration.NotApplicable.NotConstructible] is honest —
     * with the departure stimulus replaced by a no-op. Stands in for the `NamedMux` channel view whose
     * `close()` returns without departing anyone (#2372): everything downstream still passes, because
     * the survivor stays live for the trivial reason that nothing was done to it.
     */
    private fun noOpDepartureHarness(declaration: ObligationDeclaration): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            private val loom = InMemoryLoom()
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
            override fun capabilityGaps(): Map<String, String> = emptyMap()
            override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
            override fun midSessionDeathDeclaration(): ObligationDeclaration = declaration

            /** Returns `true` — claiming a departure — while departing nobody. */
            override suspend fun departCounterpart(host: Seam, joiner: Seam): Boolean = true
        }

    /** A harness over a real 2-peer link that tears on an injected death. */
    private fun tearingHarness(
        rig: TearingLinkRig,
        declaration: ObligationDeclaration,
        injects: Boolean = true,
    ): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = rig.loomPair()
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
        override fun midSessionDeathDeclaration(): ObligationDeclaration = declaration
        override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
            if (!injects) return false
            rig.drop()
            return true
        }
    }

    /**
     * The same real link and the same real injection, over seams whose `state` is pinned live — the
     * `NwSeam` #1513 observable (a dropped remote is recoverable, so `Torn` never arrives from peer
     * loss). Decorating rather than hand-writing a fabric follows [JoinerRosterObligationRigTest]: the
     * check under test reads `state` and nothing else, so a decorator is a faithful stimulus and a
     * bespoke fabric would only add surface that could itself be wrong.
     */
    private fun recoverableHarness(
        rig: TearingLinkRig,
        declaration: ObligationDeclaration,
    ): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> {
            val (h, j) = rig.loomPair()
            return RecoverableLoom(h) to RecoverableLoom(j)
        }

        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
        override fun midSessionDeathDeclaration(): ObligationDeclaration = declaration
        override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
            rig.drop()
            return true
        }
    }

    /** Holds both ends of one in-memory link so [drop] is a genuine mid-session transport death. */
    private class TearingLinkRig {
        private var link: Pair<Connection, Connection>? = null

        /** Whether [drop] ever ran — read by the ContractDiffers test to assert its own premise. */
        var injected: Boolean = false
            private set

        fun loomPair(): Pair<Loom, Loom> {
            val (h, j) = connectionPair()
            link = h to j
            return PeerMeshLoom(PeerId("rig-host"), h) to PeerMeshLoom(PeerId("rig-joiner"), j)
        }

        suspend fun drop() {
            val (h, j) = requireNotNull(link) { "the rig must weave a pair before it can drop one" }
            j.close()
            h.close()
            injected = true
        }
    }

    /** Weaves a 2-peer [peerMesh] over one [Connection] — the same shape [PeerMeshConformanceTest] uses. */
    private class PeerMeshLoom(private val self: PeerId, private val conn: Connection) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            peerMesh(
                selfId = self,
                connections = listOf(conn),
                dispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
                    "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
                },
            )
    }

    /**
     * Pins every woven seam's `state` at its first live value, so a dropped transport never surfaces
     * as `Torn`. Implemented rather than delegated for [Loom] because `host`/`join` are *default*
     * members and delegation would route straight past this override.
     */
    private class RecoverableLoom(private val inner: Loom) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = RecoverableSeam(inner.weave(rendezvous))
        override fun capability(): TransportCapability = inner.capability()
    }

    private class RecoverableSeam(private val delegate: Seam) : Seam by delegate {
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    }

    private companion object {
        const val SOME_URL = "https://github.com/tractat-us/kuilt/issues/1442"

        val CONTRACT_DIFFERS = ObligationDeclaration.NotApplicable.ContractDiffers(
            "the rig's fabric is supposed to treat a dropped remote as recoverable",
        )

        val NOT_CONSTRUCTIBLE = ObligationDeclaration.NotApplicable.NotConstructible(
            "the rig's pair is supposed to have no 2-peer transport under it to drop",
        )

        val RIG_ROSTER = JoinerRosterOrigin.FilledByConstruction(
            "a #2568 declaration rig - what this harness proves is about the DECLARATION, not the roster",
        )
    }
}
