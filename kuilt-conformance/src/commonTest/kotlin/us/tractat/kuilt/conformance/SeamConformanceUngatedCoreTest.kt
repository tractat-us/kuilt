package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the **structural** guarantee of [SeamConformanceSuite]: the core obligations
 * (host-yields-usable-seam, broadcast delivery, order, peers≥2, close-idempotency,
 * availability, both Woven-state invariants, close→Torn, absent-peer-throw, self-send-refused) are
 * **ungated** — no capability flag can suppress them.
 *
 * Two harnesses prove it, from complementary angles:
 *  - [coreObligationsRunUnderAllFalseCapabilities] drives every core obligation through a
 *    harness that declares **every** flag `false`. The capability-gated obligations would
 *    silently early-return under it; the core ones must still run their assertions and pass.
 *    This is the mechanism named in the task's ambiguity resolution.
 *  - [coreObligationsNeverReadCapabilities] drives the same obligations through a harness whose
 *    `capabilities()` / `capabilityGaps()` **throw**. Any core obligation that so much as *reads*
 *    `capabilities()` blows up and fails this test. This is the stronger guarantee — the all-false
 *    harness alone cannot catch a core obligation that soft-skips on a false flag (it would still
 *    report PASS), so making a read fatal is what actually enforces "core must NOT read
 *    capabilities() at all".
 *
 * The harnesses are **anonymous** [SeamConformanceSuite] objects built by factory functions, not
 * named subclasses — a named concrete subclass inherits the suite's `@Test` methods and the JUnit4
 * (Android) runner would try to run it as its own test class and fail to construct it.
 *
 * Each core body is invoked against a **fresh** harness ([InMemoryLoom] is a single-room mesh —
 * a second live host on one instance throws), composed inside ONE `runTest` so the whole check is
 * a single awaited [TestResult] (correct on wasmJs/JS where a bare per-obligation `runTest` returns
 * an un-awaited Promise).
 *
 * This meta-test drives the `internal` obligation body-helpers (`run*`) directly, NOT the inherited
 * `@Test`-annotated wrappers — a future edit that inlines a capability gate into a `@Test` wrapper
 * while leaving its body helper clean would slip past this guarantee undetected, so gating logic
 * must stay in the shared body helpers, never in the wrapper.
 */
class SeamConformanceUngatedCoreTest {

    private fun allFalseHarness(): SeamConformanceSuite = object : SeamConformanceSuite() {
        private val loom = InMemoryLoom()
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities = ALL_FALSE
        override fun capabilityGaps(): Map<String, String> = ALL_FALSE.falseFlags().associateWith { GAP_URL }
        override fun joinerRosterOrigin(): JoinerRosterOrigin = SHARED_IN_MEMORY_ROSTER
    }

    private fun hostileHarness(): SeamConformanceSuite = object : SeamConformanceSuite() {
        private val loom = InMemoryLoom()
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities =
            throw AssertionError("a core (ungated) obligation must never read capabilities()")
        override fun capabilityGaps(): Map<String, String> =
            throw AssertionError("a core (ungated) obligation must never read capabilityGaps()")

        // Deliberately NOT hostile. `joinerRosterOrigin()` is a fixture declaration a core obligation
        // has no business consulting either — but making it throw would test nothing this file does not
        // already test, and would break the harness for any FUTURE non-core property that legitimately
        // reads it. The guarantee under test here is about `capabilities()`; keeping the hostility to
        // the two flag accessors keeps that statement exact.
        override fun joinerRosterOrigin(): JoinerRosterOrigin = SHARED_IN_MEMORY_ROSTER
    }

    @Test
    fun coreObligationsRunUnderAllFalseCapabilities(): TestResult = runTest {
        // The rig asserts it fired, and it is DERIVED from the flag list rather than counted by hand:
        // a literal `10` here silently degrades to "some flags are false" the moment a flag is added
        // (the harness would compile — its named arguments are complete — while quietly exempting the
        // new one from the hostility this test exists to apply). Comparing the NAMES also beats
        // comparing a size: two flags could be swapped without the count moving.
        assertEquals(
            SeamCapabilities.FLAGS.map { it.first }.toSet(),
            allFalseHarness().capabilities().falseFlags(),
            "the all-false harness must declare EVERY flag false — otherwise a core obligation that " +
                "reads the flag it forgot would pass this meta-test while still being gated",
        )
        runAllCore(::allFalseHarness, this)
    }

    @Test
    fun coreObligationsNeverReadCapabilities(): TestResult = runTest {
        runAllCore(::hostileHarness, this)
    }

    /** Invoke every ungated core obligation body against a FRESH harness each. */
    private suspend fun runAllCore(harness: () -> SeamConformanceSuite, scope: TestScope) {
        harness().runHostYieldsUsableSeam(scope)
        harness().runBroadcastDeliversToJoinedPeer(scope)
        harness().runIncomingPreservesSendOrder(scope)
        harness().runPeersReportsSelfIdAndAtLeastTwo(scope)
        harness().runCloseIsIdempotent(scope)
        harness().runStateIsWovenAfterConnect(scope)
        harness().runHostStateIsWovenEvenAlone(scope)
        harness().runCloseDrivesStateTornNormal(scope)
        harness().runSendToAbsentPeerThrows(scope)
        harness().runSendToSelfIsRefused(scope)
        harness().runCloseDoesNotReportFailureAsCancellation(scope)
        harness().runAvailabilityReturnsAKnownVariant()
    }

    private companion object {
        private val ALL_FALSE = SeamCapabilities(
            reportsPeerLoss = false,
            terminatesIncomingOnClose = false,
            staysTornAfterClose = false,
            throwsOnSendToTorn = false,
            supportsSendTo = false,
            securesTransport = false,
            meshDelivery = false,
            reportsLiveCapability = false,
            collapsesPeersOnTear = false,
        )
        private const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/1404"

        /** Both harnesses are one [InMemoryLoom] driven as both ends — see [JoinerRosterOrigin]. */
        private val SHARED_IN_MEMORY_ROSTER = JoinerRosterOrigin.FilledByConstruction(
            "a shared roster: one InMemoryLoom driven as both ends, so weaving the joiner fills the " +
                "host's registry and the joiner's roster at once",
        )
    }
}
