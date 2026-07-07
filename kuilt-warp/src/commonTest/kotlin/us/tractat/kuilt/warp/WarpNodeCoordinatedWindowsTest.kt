/**
 * Unit-level interleaving tests for the coordinated-path correctness fixes:
 *
 * - **#873 — intent-register leak.** Coordinated tasks must not run through the intent-register
 *   winner election at all: consensus (the Raft log plus the leader-side commit gates) is the
 *   sole arbiter of who executes, and routing coordinated tasks through the intent path leaked
 *   one never-tombstoned intent-map entry per task.
 * - **#879 window (a) — transient dual-leader.** A deposed-but-unaware leader (role still reads
 *   `Leader`, quorum gone) must not execute a committed coordinated entry. Execution is fenced
 *   by [us.tractat.kuilt.raft.RaftNode.readIndex] — a quorum round at the leader's current term
 *   that a deposed leader cannot pass.
 * - **#879 window (b) — commit during election.** An entry whose commit every node observed
 *   while not `Leader` strands ( [us.tractat.kuilt.raft.RaftNode.committed] is replay-0). On
 *   acquiring leadership a node re-drives stranded coordinated-queue tasks by re-proposing them.
 *
 * Each test scripts one node's exact view of the failure interleaving with [FakeRaftNode];
 * the cluster-level behaviour is covered by [WarpNodeCoordinatedRaftSimTest] on real nodes.
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val WINDOWS_TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.drain() =
    drainAntiEntropy(
        WINDOWS_TEST_QUILTER_CONFIG.antiEntropyInterval,
        rounds = 5,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
    )

class WarpNodeCoordinatedWindowsTest {

    private fun TestScope.warpNode(
        seam: Seam,
        fakeRaft: FakeRaftNode,
        strategy: ClaimStrategy,
        coordinatedExecutor: suspend (TaskId) -> String,
    ) = WarpNode(
        selfId = seam.selfId,
        seam = seam,
        rosterFlow = MutableStateFlow<Set<PeerId>>(setOf(seam.selfId)),
        scope = backgroundScope,
        quilterConfig = WINDOWS_TEST_QUILTER_CONFIG,
        clock = schedulerClock(testScheduler),
        strategy = strategy,
        registry = OpRegistry(),
        coordinatedExecutor = coordinatedExecutor,
        raftNode = fakeRaft,
    )

    /**
     * #873: a coordinated task leaves **no** intent-register entry behind.
     *
     * With [ClaimStrategy.RingWithIntent] (the default), the pre-fix claim path wrote a
     * claimant entry into the intent Quilter for every task — but the Coordinated branch of
     * queue removal never tombstoned it, so each coordinated task leaked one replicated
     * intent-map entry forever. Coordinated tasks must bypass the intent election entirely.
     */
    @Test
    fun coordinatedTaskLeavesNoIntentEntry() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val seam = InMemoryLoom().host(Pattern("coord-intent-leak"))
        val fakeRaft = FakeRaftNode(initialRole = RaftRole.Leader)
        val executions = atomic(0)
        val node = warpNode(seam, fakeRaft, ClaimStrategy.RingWithIntent()) {
            executions.incrementAndGet()
            "done"
        }

        val task = TaskId("coord-leak-task")
        node.enqueue(task, CoordinationKind.Coordinated)
        drain()

        assertAll(
            { assertEquals(1, executions.value, "coordinated task executed exactly once") },
            { assertNotNull(node.results[task], "result recorded") },
            {
                assertEquals(
                    emptySet(),
                    node.intentTaskIds(),
                    "coordinated task must not leak an intent-register entry",
                )
            },
        )
        node.close()
    }

    /**
     * #879 window (a): a deposed-but-unaware leader must NOT execute a committed entry.
     *
     * The node's [RaftRole] still reads `Leader` (it has not yet learned it lost an election),
     * but it can no longer assemble a voter quorum at its term — scripted here as
     * [FakeRaftNode.readIndexBehavior] throwing [LeadershipLostException]. The unfenced pre-fix
     * code passed the bare `role == Leader` check and executed, duplicating the execution the
     * real new leader also performs. The fence must make this node stand down without executing.
     */
    @Test
    fun deposedLeaderDoesNotExecuteCommittedCoordinatedTask() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val seam = InMemoryLoom().host(Pattern("coord-deposed-leader"))
            val fakeRaft = FakeRaftNode(initialRole = RaftRole.Leader)
            fakeRaft.readIndexBehavior = { throw LeadershipLostException("deposed: no quorum at this term") }
            val executions = atomic(0)
            val node = warpNode(seam, fakeRaft, ClaimStrategy.Ring) {
                executions.incrementAndGet()
                "must-not-run"
            }

            val task = TaskId("coord-deposed-task")
            node.enqueue(task, CoordinationKind.Coordinated)
            drain()

            assertAll(
                { assertEquals(0, executions.value, "deposed leader must not invoke the executor") },
                { assertNull(node.results[task], "no result recorded by the deposed leader") },
            )
            node.close()
        }

    /**
     * #879 window (b): a commit observed while this node was not `Leader` is re-driven when
     * it acquires leadership.
     *
     * The proposal is forwarded and commits remotely; the entry replicates back and fires
     * on the committed stream while this node is still a `Follower` (mid-election, every node
     * skips it). `committed` is replay-0, so pre-fix the task stranded forever. On the role
     * transition to `Leader` the node must re-drive the stranded coordinated-queue entry —
     * re-proposing it so execution fires from a fresh committed entry — exactly once.
     */
    @Test
    fun commitObservedWithoutLeadershipIsRedrivenOnAcquisition() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val seam = InMemoryLoom().host(Pattern("coord-redrive"))
            val fakeRaft = FakeRaftNode(initialRole = RaftRole.Follower)
            // Simulate Raft §8 forwarding: a remote leader commits the proposal and the entry
            // replicates back to this follower's committed stream.
            fakeRaft.proposeBehavior = { command -> fakeRaft.pushCommitted(command) }
            val executions = atomic(0)
            val node = warpNode(seam, fakeRaft, ClaimStrategy.Ring) {
                executions.incrementAndGet()
                "redriven"
            }

            val task = TaskId("coord-stranded-task")
            node.enqueue(task, CoordinationKind.Coordinated)
            drain()
            assertEquals(0, executions.value, "stranded: commit fired while this node was a follower")

            fakeRaft.setRole(RaftRole.Leader)
            drain()

            assertAll(
                { assertEquals(1, executions.value, "stranded task re-driven exactly once") },
                { assertNotNull(node.results[task], "result recorded after re-drive") },
            )
            node.close()
        }
}
