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
import kotlinx.coroutines.awaitCancellation
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
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
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
        epoch = 0L,
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
    fun coordinatedTaskLeavesNoIntentEntry() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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

    /**
     * A task this node proposed as leader in an *earlier* tenure is re-driven when it leads again
     * (#2923). The proposed-as-leader guard may only skip a task tagged with the re-drive's own
     * tenure.
     *
     * The first propose never commits: a hung forward to a leader that then crashed. The tenure-1
     * re-drive rightly skips the task, since this node's own entry for it may still fire. After an
     * observed step-down and re-election that entry is gone for good, so the tenure-2 re-drive must
     * re-propose it.
     *
     * Two mechanisms each keep an old tag from suppressing a later re-drive: `beginTenure` clears
     * the tags, and the scan compares the tag with its own tenure. This test reds only when both are
     * gone, so it pins the behaviour, not either mechanism alone.
     */
    @Test
    fun taskProposedAsLeaderInAnEarlierTenureIsRedriven() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = InMemoryLoom().host(Pattern("coord-redrive-earlier-tenure"))
            val fakeRaft = FakeRaftNode(initialRole = RaftRole.Leader)
            val proposes = atomic(0)
            fakeRaft.proposeBehavior = { command ->
                if (proposes.incrementAndGet() == 1) awaitCancellation()
                fakeRaft.pushCommitted(command)
            }
            val executions = atomic(0)
            val node = warpNode(seam, fakeRaft, ClaimStrategy.Ring) {
                executions.incrementAndGet()
                "redriven"
            }

            val task = TaskId("coord-earlier-tenure-task")
            node.enqueue(task, CoordinationKind.Coordinated)
            drain()
            assertAll(
                { assertEquals(0, executions.value, "the first propose hangs, so nothing commits in tenure 1") },
                { assertEquals(1, proposes.value, "rig: the tenure-1 re-drive skipped the task it proposed as leader") },
            )

            fakeRaft.setRole(RaftRole.Follower)
            drain()
            fakeRaft.setRole(RaftRole.Leader)
            drain()

            assertAll(
                { assertEquals(1, executions.value, "a task tagged in an earlier tenure is re-driven exactly once") },
                { assertEquals(2, proposes.value, "rig: the tenure-2 re-drive proposed the task again") },
                { assertNotNull(node.results[task], "result recorded after re-drive") },
            )
            node.close()
        }

    /**
     * Known gap, pinned: a step-down and re-election the role collector never sees loses the
     * re-drive.
     *
     * [RaftRole.Leader] carries no term, so `Leader -> Follower -> Leader` with no collection in
     * between leaves the role StateFlow equal to what it held; the collector observes nothing. No
     * new tenure begins and no re-drive runs, so the task whose only entry was lost stays stranded.
     * The pre-#2923 code has the same gap (#879 b class). The structural fix is a term on the
     * leader role or a `RaftNode.currentTerm` flow, with the tenure keyed by term; that changes the
     * kuilt-raft API and is tracked separately (follow-up to #3008).
     *
     * This test goes red when that fix lands. Invert it then: the task should run exactly once.
     */
    @Test
    fun conflatedReElectionLosesTheRedriveKnownGap() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = InMemoryLoom().host(Pattern("coord-redrive-conflated"))
            val fakeRaft = FakeRaftNode(initialRole = RaftRole.Leader)
            val proposes = atomic(0)
            fakeRaft.proposeBehavior = { command ->
                if (proposes.incrementAndGet() == 1) awaitCancellation()
                fakeRaft.pushCommitted(command)
            }
            val executions = atomic(0)
            val node = warpNode(seam, fakeRaft, ClaimStrategy.Ring) {
                executions.incrementAndGet()
                "redriven"
            }

            val task = TaskId("coord-conflated-task")
            node.enqueue(task, CoordinationKind.Coordinated)
            drain()

            // No drain between the two: the collector never runs while the role reads Follower.
            fakeRaft.setRole(RaftRole.Follower)
            fakeRaft.setRole(RaftRole.Leader)
            drain()

            assertAll(
                { assertEquals(0, executions.value, "known gap: the unobserved re-election re-drives nothing") },
                { assertEquals(1, proposes.value, "known gap: no second propose") },
                { assertNull(node.results[task], "known gap: the task stays stranded") },
            )
            node.close()
        }
}
