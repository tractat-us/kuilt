@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Teardown-window coverage for [RaftEngine] (#1257 closeout of the #1121 decomposition).
 *
 * Two gaps the actor `finally` did not cover before this fix:
 *   1. a command still **buffered** in `cmd` (enqueued behind `Close`) had its `CompletableDeferred`
 *      dropped, hanging the caller's `await()` forever;
 *   2. the leader's `while (true)` heartbeat/quorum-check timer loops were not cancelled on `Close`,
 *      so they leaked past `close()`.
 */
class EngineTeardownTest {

    // ── Bug 1: a propose buffered behind Close is failed, not dropped ──────────

    @Test
    fun close_failsProposeBufferedBehindClose_ratherThanHanging() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        // Enqueue Close FIRST (a synchronous, non-suspending send onto the UNLIMITED channel), then
        // an UNDISPATCHED propose whose `cmd.send` runs inline before the actor gets to process Close —
        // so the Propose lands in the buffer *behind* Close and the actor breaks without ever handling
        // it. This is the exact backlog window bug 1 leaves stranded.
        leader.close()
        val proposeOutcome = CompletableDeferred<Throwable?>()
        launch(start = CoroutineStart.UNDISPATCHED) {
            proposeOutcome.complete(runCatchingCancellable { leader.propose(byteArrayOf(1)) }.exceptionOrNull())
        }

        // Bounded await: with the drain fix the deferred fails fast with NotLeaderException; without it
        // `proposeOutcome` never completes and this times out — a fast (virtual-time) failure, not a hang.
        val error = withTimeout(1_000) { proposeOutcome.await() }
        assertIs<NotLeaderException>(error, "a propose buffered behind Close must fail, not hang")
    }

    // ── Bug 2: close() cancels the leader's timer loops ───────────────────────

    @Test
    fun close_cancelsLeaderTimerLoops() = raftRunTest {
        // Dedicated child scope so we can inspect the node's launched coroutines directly.
        val nodeScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val harness = singleVoterNode(nodeScope)
        // A single voter becomes leader immediately and commits its term-start no-op at index 1 — at
        // which point becomeLeader has armed the heartbeat + quorum-check while(true) loops.
        harness.awaitCommit(1)

        val nodeJob = nodeScope.coroutineContext[Job]!!
        val activeWhileLeading = nodeJob.children.count { it.isActive }
        assertTrue(
            activeWhileLeading >= 3,
            "a leader should have the actor + heartbeat + quorum-check loops active, saw $activeWhileLeading",
        )

        harness.node.close()

        // With the timer-cancel fix, close() tears the leader's heartbeat/quorum-check loops down,
        // leaving only the persistent transport/snapshot collector coroutine active. Without it those two
        // loops leak and the active count never drops to <= 1, so this bounded wait times out fast.
        withTimeout(1_000) {
            while (nodeJob.children.count { it.isActive } > 1) delay(1)
        }
        assertTrue(
            nodeJob.children.count { it.isActive } <= 1,
            "close() must cancel the leader's heartbeat/quorum-check timer loops",
        )
    }
}
