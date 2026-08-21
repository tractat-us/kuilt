@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.LoomDefaults
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The two halves of #2386's test-side fix, pinned so neither can quietly become a no-op:
 * [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] — the deadline the loopback conformance suites inject — and
 * [LoopbackWeaveFailFast], which is what makes a deadline that generous affordable in CI.
 *
 * Neither loopback suite can be exercised from here: one needs a macOS runner and Network.framework,
 * the other needs the dylib. So this file pins the *mechanisms* they compose, over [FakeNwApi] under
 * virtual time — which is also the only way to assert a 120 s deadline without waiting 120 s.
 */
class LoopbackConformanceBackstopTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"

        /** Bounded pump: run current-virtual-time tasks until [cond] or the cap. Never hangs. */
        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }
    }

    // ── the backstop ────────────────────────────────────────────────────────────

    /**
     * The value is a **different** value from the shipped production default, and generous enough to be
     * a wedge budget rather than a performance assertion.
     *
     * The point of the whole change is that a test gate and a shipped UX deadline are different
     * decisions; a backstop that merely re-spelled `LoomDefaults.WEAVE_TIMEOUT` would leave the suites
     * exactly where #2386 found them while *looking* fixed. The upper bound is the CI budget: the two
     * K/N lanes run sequentially, so a broken fabric would pay this 60 times, and past ~37 min of
     * headroom the job is killed before the `always()` artifact upload runs — see
     * [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP]'s arithmetic, which [LoopbackWeaveFailFast] is what buys.
     */
    @Test
    fun theBackstopIsNotTheShippedProductionDefaultUnderAnotherName() = assertAll(
        {
            assertNotEquals(
                LoomDefaults.WEAVE_TIMEOUT,
                LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP,
                "a backstop equal to the shipped default is the un-injected state with extra ceremony",
            )
        },
        {
            assertEquals(
                NwLoom.DEFAULT_WEAVE_TIMEOUT,
                LoomDefaults.WEAVE_TIMEOUT,
                "rig receipt — the assertion above is only meaningful while the fabric's default really " +
                    "is the shared one; if this ever diverges, compare against both",
            )
        },
        {
            assertTrue(
                LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP > LoomDefaults.WEAVE_TIMEOUT,
                "the backstop must be LOOSER than the default, not tighter: " +
                    "$LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP",
            )
        },
        {
            assertTrue(
                LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP <= 120.seconds,
                "raising this past 120s invalidates the CI budget in its KDoc — redo the arithmetic " +
                    "there before changing this bound: $LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP",
            )
        },
    )

    /**
     * The injected value is the one [NwLoom.weave] actually enforces — a measurement, not a comparison of
     * two constants.
     *
     * The middle arm is the load-bearing one: at the shipped 30 s default plus a second, a loom carrying
     * the injected backstop is **still waiting**. That is what the suites' un-injected state could not
     * produce, and it fails if a future change routes the knob back into
     * [NwLoom.DEFAULT_WEAVE_TIMEOUT]. The outer two bracket the deadline to within 2 ms of exactly
     * [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] from both sides, so "enforced" means the injected number and
     * not merely *some* larger number.
     *
     * A lone device with nobody around is the rig on purpose: nothing is ever discovered, so the
     * formation-stuck loop parks on `armedEndpoints` and arms no re-arming timer that could keep the test
     * scheduler from going idle.
     */
    @Test
    fun theInjectedBackstopIsTheDeadlineWeaveEnforces() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NwLoom(
                FakeNwApi(FakeNwRadio(), deviceId = "solo", serviceName = "solo"),
                serviceType = TYPE,
                random = Random(0),
                weaveTimeout = LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP,
            )

            var result: Result<Seam>? = null
            launch(start = CoroutineStart.UNDISPATCHED) {
                result = runCatchingCancellable { loom.weave(Rendezvous.New(Pattern("solo"))) }
            }

            // Past the SHIPPED default, which is what an un-injected suite would have failed at.
            testScheduler.advanceTimeBy(LoomDefaults.WEAVE_TIMEOUT.inWholeMilliseconds + 1_000)
            pumpUntil { false }
            val pastShippedDefault = result

            // One millisecond short of the injected deadline.
            testScheduler.advanceTimeBy(
                LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP.inWholeMilliseconds -
                    LoomDefaults.WEAVE_TIMEOUT.inWholeMilliseconds - 1_000 - 1,
            )
            pumpUntil { false }
            val justBefore = result

            // …and two past it.
            testScheduler.advanceTimeBy(2)
            pumpUntil { result != null }
            val justAfter = result

            assertAll(
                {
                    assertNull(
                        pastShippedDefault,
                        "a second past LoomDefaults.WEAVE_TIMEOUT the weave must still be waiting — this " +
                            "is the whole point of injecting the knob: $pastShippedDefault",
                    )
                },
                {
                    assertNull(
                        justBefore,
                        "and still waiting 1ms before the injected deadline: $justBefore",
                    )
                },
                {
                    assertIs<NwUnreachableException>(
                        justAfter?.exceptionOrNull(),
                        "…and failing 1ms after it, so the enforced deadline is the injected value and " +
                            "not merely some larger one: $justAfter",
                    )
                },
            )
        }

    // ── the fail-fast latch ─────────────────────────────────────────────────────

    /**
     * The load-bearing half. Once one weave has timed out, the next `newLoomPair` must refuse to run
     * rather than buy another [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP].
     *
     * The CONTROL arm is the first one: an un-armed latch must let a pair through, or "it refuses" would
     * be satisfied by a latch that refuses unconditionally — which would take the suite to zero tests
     * while looking green in exactly this file.
     *
     * The message assertions are not decoration. This mechanism deliberately converts one contention
     * timeout into 30 reds, and the only thing standing between that and a reader concluding thirty
     * things broke is what the failure says. So it is asserted that it names the suite, says this test
     * was not attempted, and carries the original failure's own message — which since #2386 includes the
     * formation state.
     */
    @Test
    fun theLatchFailsEveryLaterPairImmediatelyAndSaysWhy() = runTest(StandardTestDispatcher()) {
        val latch = LoopbackWeaveFailFast("SuiteUnderTest")

        // CONTROL: nothing has failed, so pairs are built normally.
        latch.failIfAlreadyBroken()
        latch.failIfAlreadyBroken()
        val secondPairBuilt = runCatchingCancellable { latch.failIfAlreadyBroken() }

        // The third pair's weave times out, carrying the #2386 state in its message.
        val original = NwUnreachableException("nw weave timed out: … within 2m | state=Weaving settled=[]")
        val rethrown = assertFailsWith<NwUnreachableException> {
            latch.recordingWeaveFailure<Unit> { throw original }
        }

        val afterFailure = assertFailsWith<IllegalStateException> { latch.failIfAlreadyBroken() }

        assertAll(
            { assertTrue(secondPairBuilt.isSuccess, "an un-armed latch must build pairs: $secondPairBuilt") },
            { assertEquals(original, rethrown, "the failing test itself must still see its own failure") },
            {
                assertTrue(
                    afterFailure.message?.contains("SuiteUnderTest") == true,
                    "name the suite whose fabric broke: ${afterFailure.message}",
                )
            },
            {
                assertTrue(
                    afterFailure.message?.contains("pair #3") == true,
                    "…and WHICH pair broke it, since no portable current-test-name API exists: " +
                        "${afterFailure.message}",
                )
            },
            {
                assertTrue(
                    afterFailure.message?.contains("NOT attempted") == true,
                    "a reader must not conclude that thirty separate things broke: ${afterFailure.message}",
                )
            },
            {
                assertTrue(
                    afterFailure.message?.contains("state=Weaving settled=[]") == true,
                    "…and the original failure's own message must be carried through, or the fast red " +
                        "is less diagnostic than the slow one it replaced: ${afterFailure.message}",
                )
            },
            {
                assertEquals(
                    original,
                    afterFailure.cause,
                    "the original is the cause too, for a runner that renders cause chains",
                )
            },
        )
    }

    /**
     * The latch is armed by a **fabric** failure and by nothing else.
     *
     * Catching `Throwable` here would be the easy spelling and would be badly wrong: a plain assertion
     * failure in one conformance test would then poison the other 29, turning one honest red into thirty
     * and destroying the very diagnosis this mechanism exists to protect. (It would also swallow a
     * cancellation on the way past.) The second arm is what would red if `NwUnreachableException` were
     * ever widened to `Exception`.
     */
    @Test
    fun anOrdinaryTestFailureDoesNotArmTheLatch() = runTest(StandardTestDispatcher()) {
        val latch = LoopbackWeaveFailFast("SuiteUnderTest")
        latch.failIfAlreadyBroken()

        assertFailsWith<AssertionError> {
            latch.recordingWeaveFailure<Unit> { throw AssertionError("expected 3 peers, got 2") }
        }
        val next = runCatchingCancellable { latch.failIfAlreadyBroken() }

        assertTrue(
            next.isSuccess,
            "an assertion failure is one test's business — the fabric is still fine and the remaining " +
                "tests must run: ${next.exceptionOrNull()}",
        )
    }
}
