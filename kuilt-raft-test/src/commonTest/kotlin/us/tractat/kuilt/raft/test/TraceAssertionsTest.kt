@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.test

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TraceAssertionsTest {
    @Test fun traceAssertionsSample() = us.tractat.kuilt.raft.test.samples.traceAssertions()

    private val id = NodeId("recorded")
    private val timeout = RaftTraceEvent.Timeout(1L, id, 2L)
    private val leader = RaftTraceEvent.BecomeLeader(2L, id, 2L)
    private val request = RaftTraceEvent.ClientRequest(3L, id, 1L, 2L)

    private fun withTrace(body: suspend (MultiNodeRaftSim, FakeRaftNode) -> Unit): TestResult =
        runTest(StandardTestDispatcher(), timeout = RAFT_SIM_WEDGE_BACKSTOP) {
            val sim = MultiNodeRaftSim(listOf(id), this, backgroundScope,
                nodeFactory = { nodeId, _, _, _ -> FakeRaftNode(nodeId) })
            val fake = sim.nodes.getValue(id) as FakeRaftNode
            sim.settle()
            body(sim, fake)
        }

    private suspend fun record(sim: MultiNodeRaftSim, fake: FakeRaftNode) {
        listOf(timeout, leader, request).forEach { fake.emitTrace(it) }
        sim.settle()
    }

    @Test fun snapshotsAreDetachedAndPreserveCollectionOrder() = withTrace { sim, fake ->
        record(sim, fake)
        val snapshot = sim.traceOf(id)
        fake.emitTrace(timeout.copy(clock = 4L))
        sim.settle()
        assertAll(
            { assertEquals(listOf(timeout, leader, request), snapshot) },
            { assertEquals(snapshot + timeout.copy(clock = 4L), sim.traceOf(id)) },
        )
    }

    @Test fun ringEvictsOldEventsAndRestartClearsIt() = withTrace { sim, fake ->
        repeat(300) { fake.emitTrace(leader.copy(clock = it.toLong())) }
        sim.settle()
        val snapshot = sim.traceOf(id)
        sim.crash(id)
        assertAll(
            { assertEquals((44L..299L).toList(), snapshot.map { it.clock }) },
            { assertEquals(snapshot, sim.traceOf(id)) },
        )
        sim.restart(id)
        assertTrue(sim.traceOf(id).isEmpty())
    }

    @Test fun typedAssertionsRespectPredicatesAndReportTheTranscript() = withTrace { sim, fake ->
        record(sim, fake)
        assertEquals(leader, sim.assertTraced<RaftTraceEvent.BecomeLeader>(id) { it.term == 2L })
        sim.assertNotTraced<RaftTraceEvent.BecomeLeader>(id) { it.term == 9L }
        sim.assertNotTraced<RaftTraceEvent.AdvanceCommitIndex>(id)
        val absent = assertFailsWith<AssertionError> {
            sim.assertTraced<RaftTraceEvent.BecomeLeader>(id, "term 9") { it.term == 9L }
        }
        val wrongType = assertFailsWith<AssertionError> {
            sim.assertTraced<RaftTraceEvent.AdvanceCommitIndex>(id)
        }
        val forbidden = assertFailsWith<AssertionError> {
            sim.assertNotTraced<RaftTraceEvent.BecomeLeader>(id, "term 2") { it.term == 2L }
        }
        assertAll(
            { assertContains(absent.message.orEmpty(), "Expected BecomeLeader: term 9") },
            { assertContains(wrongType.message.orEmpty(), "Expected AdvanceCommitIndex") },
            { assertContains(forbidden.message.orEmpty(), "Unexpected BecomeLeader: term 2") },
            { assertContains(forbidden.message.orEmpty(), "matched $leader") },
            { assertContains(absent.message.orEmpty(), "node $id") },
            { assertContains(absent.message.orEmpty(), "    $timeout\n    $leader\n    $request") },
            { assertContains(forbidden.message.orEmpty(), "    $timeout\n    $leader\n    $request") },
        )
    }

    @Test fun subsequencesAllowGapsButRequireOrderAndDistinctEvents() = withTrace { sim, fake ->
        record(sim, fake)
        sim.assertTracedInOrder(id, { it == timeout }, { it == request })
        val reversed = assertFailsWith<AssertionError> {
            sim.assertTracedInOrder(id, { it == request }, { it == timeout }, message = "request before timeout")
        }
        val reused = assertFailsWith<AssertionError> {
            sim.assertTracedInOrder(id, { it == leader }, { it == leader })
        }
        assertAll(
            { assertContains(reversed.message.orEmpty(), "request before timeout: unmatched step 2 of 2") },
            { assertContains(reused.message.orEmpty(), "unmatched step 2 of 2") },
            { assertContains(reversed.message.orEmpty(), "    $timeout\n    $leader\n    $request") },
        )
    }

    @Test fun emptyTraceAndUnknownNodeAreNotConfused() = withTrace { sim, _ ->
        sim.assertTracedInOrder(id)
        sim.assertNotTraced<RaftTraceEvent.BecomeLeader>(id)
        val missing = assertFailsWith<AssertionError> { sim.assertTraced<RaftTraceEvent.BecomeLeader>(id) }
        val order = assertFailsWith<AssertionError> { sim.assertTracedInOrder(id, { true }) }
        assertAll(
            { assertContains(missing.message.orEmpty(), "Retained events: 0") },
            { assertContains(order.message.orEmpty(), "unmatched step 1 of 1") },
            { assertFailsWith<NoSuchElementException> { sim.traceOf(NodeId("unknown")) } },
            { assertFailsWith<NoSuchElementException> {
                sim.assertNotTraced<RaftTraceEvent.BecomeLeader>(NodeId("unknown"))
            } },
        )
    }
}
