@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [launchLearnerReconciler] against a scripted node, for the paths a converging simulation never
 * reaches: a refused change, a learner that has left, and a failure the loop must not absorb.
 *
 * The sim-level arms in `ServerClusterConcurrentAdmissionTest` admit every learner on the first
 * attempt and only ever grow the desired set, so the target-building and retry logic is not
 * observable there.
 */
class LearnerReconcilerTest {

    private val voters = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
    private val a = NodeId("a")
    private val b = NodeId("b")
    private val x = NodeId("x")

    /**
     * Each refusal the loop treats as "read again": while it is refused, the membership moves
     * underneath it (another change commits `x`). The retry must carry `x`, so it was rebuilt from a
     * fresh read rather than resent, which is the #3120 shape.
     */
    @Test
    fun aRefusedChangeIsRebuiltFromAFreshReadNotResent() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val refusals = listOf(
            MembershipChangeInProgressException(),
            NotLeaderException(),
            LeadershipLostException(),
        )
        for (refusal in refusals) {
            val node = ScriptedNode(ClusterConfig(voters = voters))
            node.script += { _ ->
                node.commitUnderneath(ClusterConfig(voters = voters, learners = setOf(x)))
                node.refuse(refusal)
            }
            val job = backgroundScope.launchLearnerReconciler(node, MutableStateFlow(setOf(a)))
            runCurrent()
            advanceTimeBy(BACKOFF + 1.milliseconds)
            runCurrent()
            job.cancel()

            val name = refusal::class.simpleName
            assertAll(
                { assertEquals(1, node.refusalsThrown, "$name: the rig fired once") },
                { assertEquals(2, node.calls.size, "$name: the loop survived the refusal and tried again") },
                {
                    assertEquals(
                        setOf(x, a),
                        node.calls.getOrNull(1)?.learners,
                        "$name: the retry keeps what committed meanwhile, so it was built from a fresh read",
                    )
                },
            )
        }
    }

    /**
     * Additive: a learner in the membership but no longer desired (its connection dropped, during a
     * reconnect gap) stays when another one is admitted.
     */
    @Test
    fun aLearnerThatLeftIsKeptWhenAnotherIsAdmitted() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val node = ScriptedNode(ClusterConfig(voters = voters, learners = setOf(b)))
        val job = backgroundScope.launchLearnerReconciler(node, MutableStateFlow(setOf(a)))
        runCurrent()
        job.cancel()

        assertAll(
            { assertEquals(1, node.calls.size, "one change admits a") },
            { assertEquals(setOf(a, b), node.calls.single().learners, "b, no longer desired, is not removed") },
            { assertEquals(voters, node.calls.single().voters, "the voter set is copied unchanged") },
        )
    }

    /** Only the leader writes, and it starts the moment the node becomes one. */
    @Test
    fun onlyTheLeaderChangesMembership() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val node = ScriptedNode(ClusterConfig(voters = voters), role = RaftRole.Follower)
        val job = backgroundScope.launchLearnerReconciler(node, MutableStateFlow(setOf(a)))
        runCurrent()
        val whileFollower = node.calls.size
        node.role.value = RaftRole.Leader
        runCurrent()
        job.cancel()

        assertAll(
            { assertEquals(0, whileFollower, "a follower never changes membership") },
            { assertEquals(listOf(setOf(a)), node.calls.map { it.learners }, "the new leader admits a at once") },
        )
    }

    /**
     * A failure that is not one of the three refusals escapes and ends the loop, so it fails the owning
     * scope instead of being retried silently forever.
     */
    @Test
    fun anUnexpectedFailureEndsTheLoopInsteadOfRetrying() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val node = ScriptedNode(ClusterConfig(voters = voters))
        node.script += { _ -> throw IllegalArgumentException("the engine rejected the target") }
        var escaped: Throwable? = null
        val owner = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, failure -> escaped = failure },
        )
        owner.launchLearnerReconciler(node, MutableStateFlow(setOf(a)))
        runCurrent()
        advanceTimeBy(BACKOFF * 3)
        runCurrent()

        assertAll(
            { assertIs<IllegalArgumentException>(escaped, "the failure escaped the loop to its owner") },
            { assertEquals(1, node.calls.size, "and was not retried") },
            { assertTrue(node.refusalsThrown == 0, "no refusal was scripted") },
        )
    }

    private companion object {
        /** Mirrors the reconciler's retry backoff. */
        val BACKOFF = 200.milliseconds
    }
}
