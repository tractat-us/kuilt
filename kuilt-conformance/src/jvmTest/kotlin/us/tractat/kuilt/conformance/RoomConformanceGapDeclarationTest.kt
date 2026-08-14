package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.session.SeamRoomFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/**
 * Runs [RoomConformanceSuite]'s four fault-injection-gated obligations against a harness that
 * declares [RoomConformanceSuite.FaultInjection.Unsupported] — **the first time in this suite's
 * life that its skip path has been executed at all** (#2306).
 *
 * That is the second half of the gap the issue names, and the half a `*Gap()` hook alone would not
 * have closed. Before this file the suite had exactly one subclass, the reference, which takes the
 * [RoomConformanceSuite.FaultInjection.Supported] arm on every test; the other arm had never run,
 * so nothing had ever checked that a harness taking it *survives the suite* rather than throwing an
 * `IndexOutOfBoundsException`, wedging on a wait that cannot be satisfied, or leaking a coroutine
 * into `UncompletedCoroutinesError`. An escape hatch nobody has walked through is not a proven
 * escape hatch.
 *
 * Two directions, and both matter:
 *  - a **blank** tracking URL fails every gated obligation — this is the one runtime hole the sealed
 *    fixture leaves, since `Unsupported("")` is the only way left to write a declaration that
 *    declares nothing;
 *  - a **real** tracking URL lets all four skip cleanly, which is the survivability check above.
 *
 * ## Why `jvmTest` and not `commonTest`
 *
 * These tests call the suite's `@Test` methods **directly** and assert on whether they throw. Those
 * methods return `TestResult`, which is a `typealias` for `Unit` on JVM and Kotlin/Native but a
 * `Promise` on JS/wasm — so on a browser target the call would return a pending promise, the body
 * would not have run yet, and [assertFailsWith] would pass by observing no exception. That is a
 * vacuously-green shape, so the file lives where the typealias makes the check real. The sibling
 * [SeamConformanceGapDeclarationTest] is in `commonTest` only because the single method it drives
 * (`everyFalseCapabilityDeclaresAGap`) is an ordinary function rather than a `runTest`.
 *
 * The harness is an **anonymous** [RoomConformanceSuite] built by a factory, not a named subclass —
 * a named concrete subclass inherits the suite's `@Test` methods and the JUnit4 runner would try to
 * run the whole suite against it, which is not what is under test here. Same reasoning as
 * [SeamConformanceGapDeclarationTest].
 */
class RoomConformanceGapDeclarationTest {

    /**
     * A suite whose harness cannot fault-inject and declares [trackingUrl] as the reason.
     *
     * The factories are real [SeamRoomFactory]s over a plain [InMemoryLoom] — not fakes. The four
     * obligations below bail before they build a room, so nothing here needs to work; but a harness
     * that could not even be *constructed* would make the skip path pass for the wrong reason, and a
     * real factory is no more expensive than a stub.
     */
    private fun unsupported(trackingUrl: String): RoomConformanceSuite =
        object : RoomConformanceSuite() {
            override fun newHarness(scope: CoroutineScope): RoomHarness {
                var clockMs = 0L
                val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
                val factory = SeamRoomFactory(
                    loom = InMemoryLoom(),
                    scope = scope,
                    clock = clock,
                    heartbeatConfig = fastHeartbeatConfig,
                )
                return RoomHarness(
                    hostFactory = factory,
                    joinerFactory = factory,
                    faults = FaultInjection.Unsupported(trackingUrl),
                    clock = clock,
                    advanceClock = { ms -> clockMs += ms },
                )
            }
        }

    private val tracked = "https://github.com/tractat-us/kuilt/issues/2306"

    // ── A blank declaration declares nothing, and every gated obligation says so ──

    @Test
    fun blankTrackingUrlFailsThePartitionObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.partitionedAndRecoveredFireOnLivenessTransitions() }
    }

    @Test
    fun blankTrackingUrlFailsTheResumeObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.resumeWithinWindowFiresResumed() }
    }

    @Test
    fun blankTrackingUrlFailsTheForeignTokenObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.aTokenMintedForAnotherRoomIsRefused() }
    }

    @Test
    fun blankTrackingUrlFailsTheHostLostObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.hostLostIsTerminalBroadcastIsNoOp() }
    }

    // ── A declared gap skips cleanly — the survivability half ──

    /**
     * All four gated obligations skip without throwing, without hanging and without leaking a
     * coroutine, so a harness that genuinely cannot partition its links can still subclass this
     * suite. Deliberately **one** test over all four rather than four: what is under assertion is
     * that the declared-gap path is survivable as a whole, and a per-obligation split would report
     * four identical greens for one property.
     *
     * Note what this cannot do: it cannot tell a harness that *truly* cannot fault-inject from one
     * that could and declared otherwise. Nothing at this layer can — the declaration is a claim
     * about the harness's own reach, and there is no capability on [us.tractat.kuilt.session.RoomFactory]
     * to check it against. What the arm buys is that the claim is written down and attributable.
     */
    @Test
    fun aDeclaredGapSkipsEveryGatedObligationCleanly() {
        val suite = unsupported(tracked)
        suite.partitionedAndRecoveredFireOnLivenessTransitions()
        suite.resumeWithinWindowFiresResumed()
        suite.aTokenMintedForAnotherRoomIsRefused()
        suite.hostLostIsTerminalBroadcastIsNoOp()
    }

    // ── The ungated obligations are unaffected by the declaration ──

    /**
     * A declared fault-injection gap excuses **only** the four obligations that need to break a
     * link. The rest of the suite still runs against such a harness — checked here on two of them,
     * one that touches the resume surface ([RoomConformanceSuite.joinerLearnsHostRoomIdOnAdmission],
     * which asserts against `resumeToken?.roomId` and so is the test that made the old
     * `resumeToken ?: return@runTest` skip self-contradictory) and one plain membership property.
     *
     * Without this, a future change that widened the gap to cover the whole suite would be green
     * here and nobody would notice that "declares a gap" had quietly become "opts out".
     */
    @Test
    fun aDeclaredGapDoesNotExcuseTheUngatedObligations() {
        val suite = unsupported(tracked)
        suite.joinerLearnsHostRoomIdOnAdmission()
        suite.leaveNormalFiresLeftEventAndShrinksRoster()
    }
}
