@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoom
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One step of the harness: 100 ms of virtual time and 100 ms on the injected clock, in lockstep. */
private const val REFUSAL_TICK_MS = 100L

/**
 * The step used while *waiting* for a refusal — an order of magnitude under the retry interval, so
 * a single step can never contain two retries and the first record observed is genuinely the first.
 */
private const val PROBE_STEP_MS = 10L

/** Ticks spent settling the admit handshake and letting both detectors latch "peer was seen". */
private const val SETTLE_TICKS = 3

/** The joiner-side grace before its own fabric evicts the host and the seam re-forms. */
private const val GRACE_TICKS = 4

/**
 * How long the path is down: strictly between [GRACE_TICKS] and the host's
 * `HeartbeatConfig.timeout`, which is the #1637 sandwich — the joiner reconnects, the host never
 * noticed, and every `Resume` comes back `ResumeWindowNotYetOpen`.
 */
private const val OUTAGE_TICKS = 6

/**
 * The joiner's refusal loop must **say so while it is happening**.
 *
 * A joiner whose resume is refused retries every `HeartbeatConfig.interval` for the whole
 * `reconnectWindow`. Before this test the only trace of that was a terminal `HostLost` a minute
 * later — by which point the mechanism that produced it is gone, and a real hardware capture of
 * #1637 was misdiagnosed twice because the refusal loop left no evidence of itself at all.
 *
 * [JoinerResumeMachine] now records each refusal as a [ResumeRefusal] and logs it at INFO. The
 * record is what these tests assert: the *identities and state* a diagnosis needs — which host,
 * which [RejectCode], how many attempts in, how far through the reconnect budget, and how far
 * through the dwell that discriminates a blip from a real loss. Asserting the record rather than
 * the rendered text keeps the tests about the data; `:kuilt-session` has no log-capture harness,
 * and inventing one to string-match a format would pin the least interesting half.
 *
 * The harness is the #1637 blip (see [SubTimeoutBlipResumeTest] for the full derivation of why the
 * three durations must be ordered `grace < outage < hostTimeout`); this file reuses its shape
 * because it is the one scenario that reliably produces a *sustained* refusal loop.
 */
class ResumeRefusalReportingTest {

    private val fastConfig = HeartbeatConfig(
        interval = REFUSAL_TICK_MS.milliseconds,
        timeout = 1000.milliseconds,
        reconnectWindow = 4.seconds,
    )

    @Test
    fun `a refused resume records the host the code and how far the episode has got`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun step(ms: Long) {
                nowMs += ms
                advanceTimeBy(ms)
                runCurrent()
            }
            fun tick(count: Int = 1) = repeat(count) { step(REFUSAL_TICK_MS) }

            val h = refusalHarness(clock, fastConfig)
            val joiner = h.joinerRoom as SeamRoom
            val hostId = joiner.roster.value.single().id
            val roomId = assertNotNull(joiner.resumeToken, "precondition: the joiner holds a token").roomId

            tick(SETTLE_TICKS)
            h.dropPath()
            tick(GRACE_TICKS)
            h.expireGrace()
            tick(OUTAGE_TICKS - GRACE_TICKS)
            h.restorePath()

            // The joiner is now re-woven and collecting rejects. Wait, bounded, for the first one —
            // in steps well under the retry interval, so the first record observed really is the
            // first refusal and not the second one landing inside the same coarse tick.
            var guard = 0
            while (joiner.lastResumeRefusal() == null && guard++ < 400) step(PROBE_STEP_MS)
            val first = assertNotNull(
                joiner.lastResumeRefusal(),
                "a refused resume must leave a record while the loop is still running",
            )

            // Let a few more retries land so the attempt counter is visibly advancing.
            tick(3)
            val later = assertNotNull(joiner.lastResumeRefusal(), "the record must survive the retries")

            assertAll(
                { assertEquals(hostId, first.host, "the record must name the host that refused us") },
                { assertEquals(roomId.value, first.roomId, "the record must name the room being resumed") },
                {
                    assertEquals(
                        RejectCode.ResumeWindowNotYetOpen,
                        first.code,
                        "a blip the host never observed is refused ResumeWindowNotYetOpen",
                    )
                },
                { assertEquals(1, first.attempt, "the first refusal of an episode is attempt 1") },
                {
                    assertTrue(
                        later.attempt > first.attempt,
                        "the retry loop must report each refusal, not just the first " +
                            "(first=${first.attempt} later=${later.attempt})",
                    )
                },
                {
                    assertEquals(
                        fastConfig.reconnectWindow,
                        first.budget,
                        "the record must carry the budget the elapsed time is measured against",
                    )
                },
                {
                    assertTrue(
                        first.elapsed > Duration.ZERO && first.elapsed < fastConfig.reconnectWindow,
                        "elapsed must place the refusal inside the reconnect budget (was ${first.elapsed})",
                    )
                },
                {
                    assertEquals(
                        fastConfig.timeout,
                        first.dwellTarget,
                        "the dwell target is the host's own liveness timeout",
                    )
                },
                {
                    assertEquals(
                        Duration.ZERO,
                        first.dwell,
                        "the first ResumeWindowNotYetOpen starts the dwell, so no time has elapsed in it yet",
                    )
                },
                {
                    assertTrue(
                        (later.dwell ?: Duration.ZERO) > Duration.ZERO,
                        "a sustained refusal must show the dwell advancing (was ${later.dwell})",
                    )
                },
            )
        }

    /**
     * The volume bound. The loop retries once per [HeartbeatConfig.interval], so an episode can
     * emit at most one record per interval of its budget — and the #1637 dwell cuts it far shorter
     * than that. A regression that logged per frame, or per re-weave attempt, blows this.
     */
    @Test
    fun `the refusal record advances once per retry and not once per frame`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick(count: Int = 1) = repeat(count) {
                nowMs += REFUSAL_TICK_MS
                advanceTimeBy(REFUSAL_TICK_MS)
                runCurrent()
            }

            val h = refusalHarness(clock, fastConfig)
            val joiner = h.joinerRoom as SeamRoom

            tick(SETTLE_TICKS)
            h.dropPath()
            tick(GRACE_TICKS)
            h.expireGrace()
            tick(OUTAGE_TICKS - GRACE_TICKS)
            h.restorePath()
            tick(40)

            val ceiling = (fastConfig.reconnectWindow / fastConfig.interval).toInt()
            val record = assertNotNull(joiner.lastResumeRefusal(), "the episode must have been refused")
            assertTrue(
                record.attempt in 1..ceiling,
                "an episode may report at most one refusal per retry interval " +
                    "(attempts=${record.attempt}, ceiling=$ceiling)",
            )
        }

    /** A healthy session is never refused, so it must add no refusal record — and no log line. */
    @Test
    fun `a session that is never refused records nothing`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick(count: Int = 1) = repeat(count) {
                nowMs += REFUSAL_TICK_MS
                advanceTimeBy(REFUSAL_TICK_MS)
                runCurrent()
            }

            val h = refusalHarness(clock, fastConfig)
            tick(SETTLE_TICKS + GRACE_TICKS + OUTAGE_TICKS + 20)

            assertNull(
                (h.joinerRoom as SeamRoom).lastResumeRefusal(),
                "a session that never dropped must not report a refusal",
            )
        }
}

/** The pieces of a #1637 refusal scenario a test drives: two rooms and the joiner's two fault layers. */
private class RefusalHarness(
    val joinerRoom: Room,
    private val joinerPath: FaultySeam,
    private val joinerLink: FlakyLifecycleSeam,
) {
    /** The device path goes away: frames stop both ways, but the seam is still `Woven`. */
    fun dropPath() = joinerPath.partition(Direction.Both)

    /** The fabric's grace expires: the joiner evicts the host and the seam re-forms. */
    fun expireGrace() = joinerLink.enterWeaving()

    /** The path returns: the seam re-forms in place and frames flow again. */
    fun restorePath() {
        joinerLink.recover()
        joinerPath.heal()
    }
}

/**
 * Two adopted rooms over one [InMemoryLoom], with the **joiner's** seam wrapped
 * `FlakyLifecycleSeam(FaultySeam(base))` so a test can drop the path and expire the grace
 * independently. The host's seam is untouched — that is what makes every `Resume` come back
 * `ResumeWindowNotYetOpen`.
 */
private suspend fun TestScope.refusalHarness(
    clock: () -> Instant,
    config: HeartbeatConfig,
): RefusalHarness {
    val base = InMemoryLoom()
    val hostLink = base.weave(Rendezvous.New(Pattern("refusal")))
    val joinerPath = FaultySeam(base.weave(Rendezvous.Existing(InMemoryTag("refusal"))), backgroundScope)
    val joinerLink = FlakyLifecycleSeam(joinerPath, backgroundScope)
    val factory = SeamRoomFactory(
        loom = base,
        scope = backgroundScope,
        clock = clock,
        heartbeatConfig = config,
    )
    val hostRoom = factory.adopt(hostLink, SessionRole.Host, memberName = "Host")
    val joinerRoom = factory.adopt(
        joinerLink,
        SessionRole.Joiner,
        memberName = "Joiner",
        reweave = { joinerLink },
    )
    hostRoom.roster.first { it.size == 1 }
    joinerRoom.roster.first { it.isNotEmpty() }
    return RefusalHarness(joinerRoom, joinerPath, joinerLink)
}
