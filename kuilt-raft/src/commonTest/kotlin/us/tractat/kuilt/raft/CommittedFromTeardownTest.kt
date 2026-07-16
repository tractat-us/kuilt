@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.toList
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1465: [RaftNode.committedFrom] must not leak a [ClosedSendChannelException] when its collection
 * races the node's teardown.
 *
 * The engine's command channel (`cmd`) is closed by the actor's teardown when the node scope is
 * cancelled (or a `Close` command is processed). `committedFrom` opens by sending a `CommitCut`
 * command down that channel from inside its `coroutineScope` block; when that send lost the race to
 * the teardown's `cmd.close()`, the raw `ClosedSendChannelException` propagated straight to the
 * collector — turning a routine "the node went away" into a thrown transport-teardown artifact
 * (surfaced as a flake in the real-socket reconnection suite).
 *
 * The leak was the `cmd.send` inside the `coroutineScope` block, NOT the tail's `buffer.send`: a
 * single coroutine can never `send` after its own `finally { buffer.close() }`, so the tail write is
 * structurally close-clean. This test pins the actual defect by fully tearing the node down first,
 * then collecting — deterministic under virtual time, no real dispatchers, and it reproduces the
 * exact production stack (`cmd.send` in `RaftEngine$committedFrom$1$1`).
 */
class CommittedFromTeardownTest {

    @Test
    fun committedFrom_onClosedNode_endsCleanlyWithoutClosedSendChannel() = raftRunTest {
        // Own the node on a child job of the test scope so we can close its actor independently.
        val nodeJob = Job(coroutineContext[Job])
        val nodeScope = CoroutineScope(coroutineContext + nodeJob)
        val harness = singleVoterNode(nodeScope)

        // Let it elect and commit its §5.4.2 no-op so the actor is provably live.
        harness.awaitCommit(1L)

        // Tear the node down: the actor's command loop is cancelled and closes `cmd`.
        nodeJob.cancel()
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()

        // committedFrom now hits the closed `cmd` channel on its opening CommitCut send. It must
        // terminate the stream cleanly (empty) — never leak ClosedSendChannelException (#1465).
        val leaked: Throwable? = try {
            val entries = harness.node.committedFrom(1L).toList()
            assertTrue(entries.isEmpty(), "a closed node has no cut to replay: $entries")
            null
        } catch (e: Throwable) {
            e
        }
        assertFalse(
            leaked is ClosedSendChannelException,
            "committedFrom must not leak ClosedSendChannelException on a closed node: $leaked",
        )
        assertTrue(leaked == null, "committedFrom on a closed node must complete cleanly, but threw: $leaked")
    }
}
