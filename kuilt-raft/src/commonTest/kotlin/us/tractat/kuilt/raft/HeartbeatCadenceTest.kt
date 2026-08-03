@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Regression for #1991: the leader's heartbeat loop waits the configured [RaftConfig.heartbeatInterval],
 * not that interval floored to whole milliseconds.
 *
 * The loop used to call `delay(heartbeatInterval.inWholeMilliseconds)`. `inWholeMilliseconds` **truncates
 * down**, so every sub-millisecond interval became `delay(0)` and the loop spun as fast as the dispatcher
 * would schedule it — an unbounded hot loop on the leader in production, and under virtual time a loop that
 * never yields the clock, so the test hangs rather than fails. It now passes the [kotlin.time.Duration] to
 * `delay`, whose own quantisation rounds **up** to the next whole millisecond and therefore has no zero to
 * fall into.
 *
 * ### Why this test probes at 1.9 ms and not at the sub-millisecond value from the issue
 *
 * Deliberately, and it is the whole reason this test is safe to write. `RaftConfig` now refuses a
 * sub-millisecond heartbeat outright (see `RaftConfigTimingValidationTest`), so that value is no longer
 * constructible — but more importantly, a test that *did* reproduce the spin would hang the suite instead
 * of failing it, which is precisely the diagnostic this issue is about.
 *
 * So the probe sits at the **worst truncation the new floor still admits**: any interval in `(1ms, 2ms)`
 * floors to `1ms` while the `Duration` overload gives `2ms`, a clean 2× separation in cadence. Before the
 * fix this test is red with a *wrong number of heartbeats*; it is never at risk of hanging, because
 * `delay(1)` is a real wait either way.
 *
 * ### What is counted
 *
 * The leader emits one `AppendEntries` per peer per heartbeat tick, and in a quiescent cluster nothing else
 * emits one: `sendAppendEntries` is re-entered from `onAppendEntriesResponse` only on a *rejected* response,
 * and this test never proposes. So `AppendEntries` frames on one leader→follower link over a bounded window
 * of virtual time *are* the tick count.
 */
internal class HeartbeatCadenceTest {

    /**
     * The regression. `1900.microseconds` floors to `1ms` and rounds up to `2ms`, so over [WINDOW] the two
     * behaviours differ by exactly a factor of two — 20 heartbeats against 40.
     */
    @Test
    fun aHeartbeatIntervalIsNotFlooredToWholeMilliseconds() = raftRunTest {
        assertAll(
            { assertEquals(1L, HEARTBEAT.inWholeMilliseconds, "the premise: this interval floors to 1 ms…") },
            { assertEquals(2L, HEARTBEAT.roundedUpToWholeMilliseconds(), "…and rounds up to 2 ms") },
        )
        assertHeartbeatsOverWindow(interval = HEARTBEAT, expected = 20)
    }

    /**
     * The other half of the same change: for a whole-millisecond interval the floor and the round-up agree,
     * so every interval any call site in this repo actually sets keeps the cadence it had. Pinned because
     * "stop truncating" is only obviously safe if it is a no-op wherever truncation was already exact.
     */
    @Test
    fun aWholeMillisecondIntervalKeepsItsCadence() = raftRunTest {
        assertAll(
            { assertEquals(2L, 2.milliseconds.inWholeMilliseconds, "floor and round-up agree at whole milliseconds…") },
            { assertEquals(2L, 2.milliseconds.roundedUpToWholeMilliseconds(), "…so the fix changes nothing here") },
        )
        assertHeartbeatsOverWindow(interval = 2.milliseconds, expected = 20)
    }

    /**
     * Elect a leader on a three-voter cluster configured with [interval], then count the `AppendEntries` it
     * puts on one follower's link across [WINDOW] of virtual time.
     *
     * Time is advanced by a plain bounded `delay` — never `advanceUntilIdle()`, which would spin forever
     * against the perpetually re-arming heartbeat and election timers.
     */
    private suspend fun TestScope.assertHeartbeatsOverWindow(
        interval: Duration,
        expected: Int,
    ) {
        val config = RaftConfig(
            electionTimeoutMin = 10.milliseconds,
            electionTimeoutMax = 20.milliseconds,
            heartbeatInterval = interval,
            expectVirtualTime = true,
            random = Random(RAFT_TEST_SEED),
        )
        val sim = raftSim(this, backgroundScope, n = 3, config = config)
        val leader = sim.awaitLeader()
        val leaderId = checkNotNull(leader.leader.value) { "a leader must name itself" }
        val followerId = sim.nodeIds.first { it != leaderId }
        sim.settle()

        sim.network.sent.clear()
        sim.network.recording = true
        delay(WINDOW)
        sim.network.recording = false

        val ticks = sim.network.sent.count {
            it.from == leaderId && it.to == followerId && it.message is RaftMessage.AppendEntries
        }
        assertAll(
            {
                assertEquals(
                    leaderId,
                    sim.leader()?.leader?.value,
                    "leadership must hold across the window, or the count means nothing",
                )
            },
            {
                // ±1 absorbs where the window's edges fall relative to the loop's phase; it is an order of
                // magnitude tighter than the gap between the two behaviours being told apart.
                assertTrue(
                    ticks in (expected - 1)..(expected + 1),
                    "expected ~$expected heartbeats on $leaderId→$followerId over $WINDOW at " +
                        "heartbeatInterval=$interval, but counted $ticks. A count near ${expected * 2} means the " +
                        "interval was floored to whole milliseconds instead of passed to delay as a Duration (#1991).",
                )
            },
        )
    }

    private companion object {
        /**
         * The worst truncation `RaftConfig`'s new floor still admits: floors to 1 ms, rounds up to 2 ms.
         * Chosen so the pre-fix behaviour is a wrong cadence rather than the `delay(0)` spin — see the
         * class KDoc.
         */
        val HEARTBEAT = 1900.microseconds

        /** Twenty heartbeats at the round-up cadence, forty at the floored one. Both far inside a leader term. */
        val WINDOW = 40.milliseconds

        /**
         * `kotlinx.coroutines`' own `Duration.toDelayMillis()`, which `delay(Duration)` applies: round **up**
         * to the next whole millisecond. Restated here (it is `internal` to the library) so this test asserts
         * the contract it depends on rather than assuming it — were `delay(Duration)` ever to start flooring,
         * the fix would silently stop being one and the premise assertions above would redden.
         */
        fun Duration.roundedUpToWholeMilliseconds(): Long =
            if (this > Duration.ZERO) (this + 999_999.nanoseconds).inWholeMilliseconds else 0L
    }
}
