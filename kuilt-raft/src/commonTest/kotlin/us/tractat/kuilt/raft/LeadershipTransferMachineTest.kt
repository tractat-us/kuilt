@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.raft.internal.LeadershipTransferMachine
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [LeadershipTransferMachine] driven directly — it is a plain, actor-confined decision
 * machine, so a single-instance test is legitimate (not a hand-rolled cluster). Covers the generation
 * guard on the auto-abandon timer (#1232): a late `TransferTimeout` from an already-resolved transfer's
 * timer must not abort the *next* transfer.
 */
internal class LeadershipTransferMachineTest {

    /**
     * #1232 — stale auto-timeout has no effect on a successor transfer.
     *
     * Under a real dispatcher a transfer's timer can be past its `delay` and mid-`trySend` when the
     * transfer resolves by another path (an explicit cancel); `timeoutJob.cancel()` cannot un-send that
     * `TransferTimeout`. If a follow-up transfer has since started, an ungenerationed `onTimeout` would
     * abort it as "timed out". This test reproduces the interleave at the machine level: fire transfer 1's
     * timer (capturing its epoch), resolve transfer 1 by cancel, start transfer 2, then deliver transfer
     * 1's stale timeout. The stale timeout must be a no-op; transfer 2's own timeout must still abort it.
     */
    @Test
    fun staleTimeout_fromResolvedTransfer_doesNotAbortSuccessor() = raftRunTest(timeout = 5.seconds) {
        val targetB = NodeId("B")
        val targetC = NodeId("C")
        val window = FAST_RAFT_CONFIG.electionTimeoutMax + 1.milliseconds

        var lastSignaledEpoch: Long? = null
        val machine = LeadershipTransferMachine(
            scope = backgroundScope,
            raftConfig = FAST_RAFT_CONFIG,
            signalTimeout = { epoch -> lastSignaledEpoch = epoch },
        )

        // Transfer 1: start it, then let its auto-timeout timer fire so we capture the epoch it signals —
        // the stale TransferTimeout that is now "in flight" toward the actor for generation 1.
        val d1 = CompletableDeferred<Unit>()
        assertTrue(machine.start(targetB, d1))
        advanceTimeBy(window); runCurrent()
        val staleEpoch = lastSignaledEpoch
        assertTrue(staleEpoch != null, "transfer 1's timer must have signalled its epoch")

        // Transfer 1 resolves by a different path (explicit cancel) BEFORE the stale timeout is processed.
        assertEquals(targetB, machine.onCancel())
        assertTrue(d1.isCompleted)

        // Transfer 2 starts — a fresh generation with its own deferred.
        val d2 = CompletableDeferred<Unit>()
        assertTrue(machine.start(targetC, d2))

        // The stale TransferTimeout for transfer 1 is finally processed. It must be a no-op.
        val abandonedStale = machine.onTimeout(staleEpoch)
        val d2CompletedAfterStale = d2.isCompleted

        // Transfer 2's OWN timeout (current generation) must still abort it — the guard doesn't over-reject.
        advanceTimeBy(window); runCurrent()
        val ownEpoch = lastSignaledEpoch
        val abandonedOwn = machine.onTimeout(ownEpoch!!)
        val d2CompletedAfterOwn = d2.isCompleted

        assertAll(
            { assertNull(abandonedStale, "a stale timeout from a resolved transfer must be a no-op (return null)") },
            { assertFalse(d2CompletedAfterStale, "transfer 2 must NOT be aborted by transfer 1's stale timeout") },
            { assertTrue(ownEpoch != staleEpoch, "transfer 2 must carry a distinct generation from transfer 1") },
            { assertEquals(targetC, abandonedOwn, "transfer 2's own-generation timeout must abort it") },
            { assertTrue(d2CompletedAfterOwn, "transfer 2's deferred must be failed by its own timeout") },
        )
    }
}
