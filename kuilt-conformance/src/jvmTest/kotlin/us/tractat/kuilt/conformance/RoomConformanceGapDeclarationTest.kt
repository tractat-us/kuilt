package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/**
 * Runs [RoomConformanceSuite]'s five fault-injection-gated obligations, and its nineteen return rows, against a harness that
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
 *  - a **real** tracking URL lets all twenty-four skip cleanly, which is the survivability check above.
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
                val loom = InMemoryLoom()
                // One device per factory, as [RoomHarness] requires: a shared store would make the
                // joiner a second tab of the host's device.
                fun factory() = SeamRoomFactory(
                    loom = loom,
                    scope = scope,
                    clock = clock,
                    deviceKeyStore = InMemoryDeviceKeyStore(),
                    heartbeatConfig = fastHeartbeatConfig,
                )
                return RoomHarness(
                    hostFactory = factory(),
                    joinerFactory = factory(),
                    faults = FaultInjection.Unsupported(trackingUrl),
                    clock = clock,
                    advanceClock = { ms -> clockMs += ms },
                )
            }

            override fun newReturnHarness(scope: TestScope): ReturnFixture = ReturnFixture.Unsupported(trackingUrl)
        }

    private val tracked = "https://github.com/tractat-us/kuilt/issues/2306"

    // ── A blank declaration declares nothing, and every gated obligation says so ──

    @Test
    fun blankTrackingUrlFailsThePartitionObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.partitionedAndRecoveredFireOnLivenessTransitions() }
    }

    @Test
    fun blankTrackingUrlFailsTheHostLostObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.hostLostIsTerminalBroadcastIsNoOp() }
    }

    @Test
    fun blankTrackingUrlFailsTheTeardownFixturePrecondition() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.theTeardownFaultReallyFires() }
    }

    @Test
    fun blankTrackingUrlFailsTheFailingTeardownIdempotencyObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.leaveIsIdempotentEvenWhenTeardownFails() }
    }

    @Test
    fun blankTrackingUrlFailsTheSlowTeardownCancellationObligation() {
        val suite = unsupported("")
        assertFailsWith<AssertionError> { suite.leaveDoesNotMintACancellationWhenTeardownIsSlow() }
    }

    /**
     * The return rows (#3159, Task 1.9) are gated by their own fixture, [ReturnFixture], which this
     * harness declares [ReturnFixture.Unsupported] with the same URL. A blank one fails every row.
     */
    @Test
    fun blankTrackingUrlFailsEveryReturnRow() {
        val suite = unsupported("")
        assertAll(
            { assertFailsWith<AssertionError> { suite.returnRestoresHeldSeatSameId() } },
            { assertFailsWith<AssertionError> { suite.resumeWithinWindowFiresResumed() } },
            { assertFailsWith<AssertionError> { suite.differentKeyOnLiveIdIsRefused() } },
            { assertFailsWith<AssertionError> { suite.aDifferentKeyOnAPartitionedIdReleasesTheSeat() } },
            { assertFailsWith<AssertionError> { suite.processRebuiltInsideWindowRestores() } },
            { assertFailsWith<AssertionError> { suite.processRebuiltWithFreshIdRestores() } },
            { assertFailsWith<AssertionError> { suite.aSecondLiveProcessGetsAnAdditionalSeat() } },
            { assertFailsWith<AssertionError> { suite.aJoinerPresentingTheHostsKeyGetsAJoinerSeat() } },
            { assertFailsWith<AssertionError> { suite.aReturnAfterTheSeatWentIsReadmitted() } },
            { assertFailsWith<AssertionError> { suite.aReadmittedJoinerDropsAPeerWhoLeftDuringTheOutage() } },
            { assertFailsWith<AssertionError> { suite.anAwayJoinerHearsASupersedeThroughItsRosterRebuild() } },
            { assertFailsWith<AssertionError> { suite.returnInsideGrantNoVetting() } },
            { assertFailsWith<AssertionError> { suite.expiredGrantIsVetted() } },
            { assertFailsWith<AssertionError> { suite.revokedSubjectIsVetted() } },
            { assertFailsWith<AssertionError> { suite.aPlantedGoodPassLetsTheDeviceInUnvetted() } },
            { assertFailsWith<AssertionError> { suite.grantForAnotherRoomIsIgnored() } },
            { assertFailsWith<AssertionError> { suite.grantForAnotherSubjectIsIgnored() } },
            { assertFailsWith<AssertionError> { suite.untrustedIssuerIsIgnored() } },
            { assertFailsWith<AssertionError> { suite.aProcessUnderASupersededIdEndsSuperseded() } },
        )
    }

    // ── A declared gap skips cleanly — the survivability half ──

    /**
     * All twenty-four gated obligations skip without throwing, without hanging and without leaking a
     * coroutine, so a harness that genuinely cannot partition its links can still subclass this
     * suite. Deliberately **one** test over all twenty-four rather than twenty-four: what is under assertion is
     * that the declared-gap path is survivable as a whole, and a per-obligation split would report
     * twenty-four identical greens for one property.
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
        suite.hostLostIsTerminalBroadcastIsNoOp()
        suite.theTeardownFaultReallyFires()
        suite.leaveIsIdempotentEvenWhenTeardownFails()
        suite.leaveDoesNotMintACancellationWhenTeardownIsSlow()
        suite.returnRestoresHeldSeatSameId()
        suite.resumeWithinWindowFiresResumed()
        suite.differentKeyOnLiveIdIsRefused()
        suite.aDifferentKeyOnAPartitionedIdReleasesTheSeat()
        suite.processRebuiltInsideWindowRestores()
        suite.processRebuiltWithFreshIdRestores()
        suite.aSecondLiveProcessGetsAnAdditionalSeat()
        suite.aJoinerPresentingTheHostsKeyGetsAJoinerSeat()
        suite.aReturnAfterTheSeatWentIsReadmitted()
        suite.aReadmittedJoinerDropsAPeerWhoLeftDuringTheOutage()
        suite.anAwayJoinerHearsASupersedeThroughItsRosterRebuild()
        suite.returnInsideGrantNoVetting()
        suite.expiredGrantIsVetted()
        suite.revokedSubjectIsVetted()
        suite.aPlantedGoodPassLetsTheDeviceInUnvetted()
        suite.grantForAnotherRoomIsIgnored()
        suite.grantForAnotherSubjectIsIgnored()
        suite.untrustedIssuerIsIgnored()
        suite.aProcessUnderASupersededIdEndsSuperseded()
    }

    // ── The forging hooks inside a supported return rig (#3710) ──

    /**
     * A suite whose return rig is the reference, so [ReturnFixture.Supported], but whose two forging
     * hooks are declared gaps with [trackingUrl]: the shape of a harness over a real fabric that can
     * drop and heal its links but cannot rewrite a Proof or choose a process's peer id.
     */
    private fun forgingUnsupported(trackingUrl: String): RoomConformanceSuite =
        object : RoomConformanceSuite() {
            override fun newReturnHarness(scope: TestScope): ReturnFixture = ReturnFixture.Supported(
                scope.referenceReturnHarness(
                    passPlanting = { PassPlanting.Unsupported(trackingUrl) },
                    reusedIdLaunch = { ReusedIdLaunch.Unsupported(trackingUrl) },
                ),
            )
        }

    /** A blank declaration of either forging hook fails each row it gates, as a blank [ReturnFixture] does. */
    @Test
    fun blankTrackingUrlFailsEveryForgingRow() {
        val suite = forgingUnsupported("")
        assertAll(
            { assertFailsWith<AssertionError> { suite.aPlantedGoodPassLetsTheDeviceInUnvetted() } },
            { assertFailsWith<AssertionError> { suite.grantForAnotherRoomIsIgnored() } },
            { assertFailsWith<AssertionError> { suite.grantForAnotherSubjectIsIgnored() } },
            { assertFailsWith<AssertionError> { suite.untrustedIssuerIsIgnored() } },
            { assertFailsWith<AssertionError> { suite.aProcessUnderASupersededIdEndsSuperseded() } },
        )
    }

    /**
     * A tracked declaration of both hooks skips the five rows they gate without throwing, hanging or
     * leaking a coroutine, inside a return rig that is otherwise supported: the rows gate on the hook
     * before they build a room.
     */
    @Test
    fun aDeclaredForgingGapSkipsItsRowsCleanly() {
        val suite = forgingUnsupported(tracked)
        suite.aPlantedGoodPassLetsTheDeviceInUnvetted()
        suite.grantForAnotherRoomIsIgnored()
        suite.grantForAnotherSubjectIsIgnored()
        suite.untrustedIssuerIsIgnored()
        suite.aProcessUnderASupersededIdEndsSuperseded()
    }

    // ── The ungated obligations are unaffected by the declaration ──

    /**
     * A declared fault-injection gap excuses **only** the obligations that need to break a link. The
     * rest of the suite still runs against such a harness — checked here on the room-id property
     * ([RoomConformanceSuite.joinerLearnsHostRoomIdOnAdmission]), one plain membership property, and
     * `Room.leave`'s documented obligations, which are ungated core precisely because no fabric
     * may excuse them (#2501).
     *
     * Without this, a future change that widened the gap to cover the whole suite would be green
     * here and nobody would notice that "declares a gap" had quietly become "opts out".
     */
    @Test
    fun aDeclaredGapDoesNotExcuseTheUngatedObligations() {
        val suite = unsupported(tracked)
        suite.joinerLearnsHostRoomIdOnAdmission()
        suite.leaveNormalFiresLeftEventAndShrinksRoster()
        suite.leaveIsIdempotent()
        suite.leaveDoesNotReportFailureAsCancellation()
        suite.channelStateTornAfterLeave()
    }
}
