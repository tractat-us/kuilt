@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [LearnerGrace] driving the removing reconciler, against a scripted node that records every target
 * it is handed. Recording every attempt is what lets an arm assert a learner was **never** absent: a
 * removal and re-admission inside one virtual instant would be invisible to anything sampling the
 * membership.
 */
class LearnerGraceTest {

    private val voters = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
    private val a = NodeId("a")
    private val b = NodeId("b")
    private val c = NodeId("c")
    private val d = NodeId("d")
    private val grace = 2.seconds

    /** Mirrors the reconciler's retry backoff. */
    private val BACKOFF = 200.milliseconds

    @Test
    fun aLearnerGoneForTheWholeGraceIsRemoved() = graceTest(connectedAtStart = setOf(a, b)) { node, connected ->
        connected.value = setOf(a)
        runCurrent()
        advanceTimeBy(grace - 1.milliseconds)
        runCurrent()
        val beforeExpiry = node.calls.toList()
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(emptyList(), beforeExpiry, "b is kept while its grace runs") },
            { assertEquals(listOf(setOf(a)), node.calls.map { it.learners }, "b is removed as the grace ends") },
        )
    }

    /**
     * The removal is refused once, so b really is still in the config after its grace ran out. The
     * retry must come one backoff later, not a whole second grace later: an expired learner is not
     * graced again.
     */
    @Test
    fun aRefusedRemovalIsRetriedWithoutASecondGrace() = graceTest(connectedAtStart = setOf(a, b)) { node, connected ->
        node.script += { _ -> node.refuse(MembershipChangeInProgressException()) }
        connected.value = setOf(a)
        runCurrent()
        advanceTimeBy(grace + 1.milliseconds)
        runCurrent()
        advanceTimeBy(BACKOFF + 1.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(1, node.refusalsThrown, "the rig fired: the first removal was refused") },
            {
                assertEquals(
                    listOf(setOf(a), setOf(a)),
                    node.calls.map { it.learners },
                    "the retry removes b one backoff after the refusal",
                )
            },
        )
    }

    /**
     * Each disconnect gets a full grace of its own. b drops, comes back inside the grace, and drops
     * again before the first grace would have ended: it must stay until a whole grace after the
     * second disconnect.
     */
    @Test
    fun aReconnectInsideTheGraceKeepsTheLearnerAndRestartsTheGrace() = graceTest(connectedAtStart = setOf(a, b)) { node, connected ->
        connected.value = setOf(a)
        runCurrent()
        advanceTimeBy(grace / 2)
        connected.value = setOf(a, b)
        runCurrent()
        advanceTimeBy(grace / 4)
        connected.value = setOf(a)
        runCurrent()
        advanceTimeBy(grace - 1.milliseconds)
        runCurrent()
        val beforeSecondExpiry = node.calls.toList()
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    emptyList(),
                    beforeSecondExpiry,
                    "b is never removed before a whole grace has passed since its latest disconnect",
                )
            },
            { assertEquals(listOf(setOf(a)), node.calls.map { it.learners }, "then the second grace removes it") },
        )
    }

    /** A learner the config holds but nobody has seen connected, as after a voter restart. */
    @Test
    fun aLearnerNobodyHasSeenGetsAFullGrace() = graceTest(
        connectedAtStart = emptySet(),
        initialLearners = setOf(c),
    ) { node, _ ->
        runCurrent()
        advanceTimeBy(grace - 1.milliseconds)
        runCurrent()
        val beforeExpiry = node.calls.toList()
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(emptyList(), beforeExpiry, "c is not removed by the first change built after it was read") },
            { assertEquals(listOf(emptySet()), node.calls.map { it.learners }, "c is removed once its grace runs out") },
        )
    }

    @Test
    fun anArrivalIsAdmittedAtOnceWhileAnotherIsInItsGrace() = graceTest(connectedAtStart = setOf(a, b)) { node, connected ->
        connected.value = setOf(a, d)
        runCurrent()
        val atArrival = node.calls.map { it.learners }
        advanceTimeBy(grace + 1.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(listOf(setOf(a, b, d)), atArrival, "d is added at once, b kept while in its grace") },
            { assertEquals(setOf(a, d), node.calls.last().learners, "then b is removed") },
            { assertEquals(voters, node.calls.last().voters, "the voter set is never touched") },
        )
    }

    @Test
    fun aZeroGraceRemovesOnDisconnect() = graceTest(connectedAtStart = setOf(a, b), grace = Duration.ZERO) { node, connected ->
        connected.value = setOf(a)
        runCurrent()
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(setOf(a)), node.calls.map { it.learners })
    }

    @Test
    fun aNegativeGraceIsRefused() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        assertFailsWith<IllegalArgumentException> {
            LearnerGrace(MutableStateFlow(emptySet()), (-1).milliseconds, backgroundScope)
        }
    }

    private fun graceTest(
        connectedAtStart: Set<NodeId>,
        initialLearners: Set<NodeId> = connectedAtStart,
        grace: Duration = this.grace,
        body: suspend TestScope.(ScriptedNode, MutableStateFlow<Set<NodeId>>) -> Unit,
    ) = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val connected = MutableStateFlow(connectedAtStart)
        val node = ScriptedNode(ClusterConfig(voters = voters, learners = initialLearners))
        backgroundScope.launchLearnerReconciler(node, LearnerGrace(connected, grace, backgroundScope))
        runCurrent()
        body(node, connected)
    }
}
