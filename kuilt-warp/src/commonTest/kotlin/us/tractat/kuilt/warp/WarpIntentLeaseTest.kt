@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Returns a clock that reads virtual time from [scheduler], so settle/lease timers advance with `delay()`. */
private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private val TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/**
 * Lease-backstop timing test. Runs under [StandardTestDispatcher] for deterministic ordering
 * (concurrent settle/lease timers + replication round-trips) and drives virtual time in bounded
 * [advanceTimeBy] + [runCurrent] steps — never [kotlinx.coroutines.test.advanceUntilIdle], which
 * spins the Quilter's unconditional anti-entropy loop forever.
 */
class WarpIntentLeaseTest {

    /** Advance virtual time by [duration] in small steps, pumping coroutines after each. */
    private fun TestScope.advance(duration: Duration, step: Duration = 100.milliseconds) {
        val steps = (duration / step).toInt().coerceAtLeast(1)
        repeat(steps) { advanceTimeBy(step); runCurrent() }
    }

    /**
     * A winner that never completes (stuck-but-alive) must not strand the task: after the
     * [ClaimStrategy.RingWithIntent.claimLease] elapses, the losing peer takes over and a
     * result lands.
     *
     * Setup forces a *real* disagreement window rather than relying on transient churn: the
     * two nodes are handed **different rosters**. The stuck winner sees only itself, so it owns
     * every task and claims `t`; the loser sees both peers, so for a task that hashes to the
     * loser it too believes it owns `t` and announces. Once the intent register converges both
     * are claimants; `winner(t)` is the lower-`PeerId` winner, so the loser stands down. The
     * winner is alive (in the loser's roster) but its executor blocks forever — no partition
     * fires, so only the lease backstop can rescue the task. After `claimLease`,
     * `winnerAfterLease` excludes the timed-out winner and the loser takes over.
     */
    @Test
    fun stuckWinnerHandsOffAfterLease() = runTest(StandardTestDispatcher(), timeout = 30.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("lease-stuck"))
        val seamB = loom.join(InMemoryTag("b"))
        val clock = schedulerClock(testScheduler)
        val strat = ClaimStrategy.RingWithIntent(settleWindow = 500.milliseconds, claimLease = 5.seconds)
        val neverDone = CompletableDeferred<Unit>()
        val executedBy = mutableMapOf<TaskId, String>()
        val lock = reentrantLock()

        // The lower-PeerId node is the agreed winner; make ITS executor block forever.
        val winnerSeam = listOf(seamA, seamB).minByOrNull { it.selfId.value }!!
        val loserSeam = listOf(seamA, seamB).maxByOrNull { it.selfId.value }!!

        // Different roster views force the disagreement window deterministically:
        //  - winner sees only itself  → owns every task
        //  - loser sees both          → owns the tasks that hash to it
        val winnerRoster = MutableStateFlow(setOf(winnerSeam.selfId))
        val loserRoster = MutableStateFlow(setOf(winnerSeam.selfId, loserSeam.selfId))

        // Pick a task that the loser owns under the two-peer ring (so the loser announces too).
        val pairRing = RosterSnapshot(setOf(winnerSeam.selfId, loserSeam.selfId)).toTaskRing()
        val t = (1..100).map { TaskId("contested-$it") }
            .first { pairRing.owner(it) == loserSeam.selfId }

        fun node(seam: Seam, roster: MutableStateFlow<Set<PeerId>>, stuck: Boolean) = WarpNode(
            selfId = seam.selfId, seam = seam, rosterFlow = roster,
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = strat,
            executor = { taskId ->
                if (stuck) neverDone.await()
                lock.withLock { executedBy[taskId] = seam.selfId.value }
                "r-${taskId.value}"
            },
        )
        val winnerNode = node(winnerSeam, winnerRoster, stuck = true)
        val loserNode = node(loserSeam, loserRoster, stuck = false)

        // Stabilise membership and replication.
        advance(1.seconds)
        winnerNode.enqueue(t)

        // Settle window + replication: the winner claims, the loser stands down.
        advance(1.seconds)
        assertTrue(lock.withLock { executedBy.isEmpty() }, "stuck winner produced no result before the lease")

        // Advance past the lease — the loser must take over the stuck winner's task.
        advance(6.seconds)

        assertEquals(
            loserSeam.selfId.value,
            lock.withLock { executedBy[t] },
            "task must complete via lease hand-off despite the stuck winner",
        )
        assertEquals(setOf(t), loserNode.results.taskIds, "loser's board records the lease result")

        winnerNode.close(); loserNode.close()
    }
}
