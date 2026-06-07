@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceTest {

    @Test fun initialElection_emits_BecomeLeader() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val allEvents = mutableListOf<RaftTraceEvent>()
        val collectJobs = sim.nodes.values.map { node ->
            launch { node.trace.collect { allEvents.add(it) } }
        }
        awaitLeader(sim)
        delay(20)
        collectJobs.forEach { it.cancel() }

        val leaderEvents = allEvents.filterIsInstance<RaftTraceEvent.BecomeLeader>()
        assertTrue(leaderEvents.isNotEmpty(), "Expected at least one BecomeLeader event")
        // Election safety via trace: no two nodes become leader in the same term
        leaderEvents.groupBy { it.term }.forEach { (term, leaders) ->
            assertEquals(1, leaders.size, "Multiple leaders in term $term: ${leaders.map { it.node }}")
        }
    }

    @Test fun initialElection_emits_Timeout_and_RequestVote() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val allEvents = mutableListOf<RaftTraceEvent>()
        val collectJobs = sim.nodes.values.map { node ->
            launch { node.trace.collect { allEvents.add(it) } }
        }
        awaitLeader(sim)
        delay(20)
        collectJobs.forEach { it.cancel() }

        assertTrue(allEvents.filterIsInstance<RaftTraceEvent.Timeout>().isNotEmpty(),
            "Expected at least one Timeout event")
        assertTrue(allEvents.filterIsInstance<RaftTraceEvent.RequestVote>().isNotEmpty(),
            "Expected at least one RequestVote event")
    }

    @Test fun proposal_emits_ClientRequest_then_AdvanceCommitIndex() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val events = mutableListOf<RaftTraceEvent>()
        val job = launch { leader.trace.collect { events.add(it) } }
        leader.propose(byteArrayOf(42))
        delay(20)
        job.cancel()

        val clientReqs = events.filterIsInstance<RaftTraceEvent.ClientRequest>()
        val advances = events.filterIsInstance<RaftTraceEvent.AdvanceCommitIndex>()
        assertTrue(clientReqs.isNotEmpty(), "Expected ClientRequest event")
        assertTrue(advances.isNotEmpty(), "Expected AdvanceCommitIndex event")
        // ClientRequest must precede AdvanceCommitIndex
        val firstReq = clientReqs.minBy { it.clock }
        val firstAdvance = advances.minBy { it.clock }
        assertTrue(
            firstReq.clock < firstAdvance.clock,
            "ClientRequest(clock=${firstReq.clock}) must precede AdvanceCommitIndex(clock=${firstAdvance.clock})",
        )
    }

    @Test fun stepDown_emits_BecomeFollower() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val events = mutableListOf<RaftTraceEvent>()
        val job = launch { leader.trace.collect { events.add(it) } }
        // Isolate leader so the other two elect a new leader and send higher-term AppendEntries back
        sim.partition(setOf(leaderId), sim.nodes.keys.filter { it != leaderId }.toSet())
        delay(80)
        sim.heal()
        delay(50)
        job.cancel()

        val stepDowns = events.filterIsInstance<RaftTraceEvent.BecomeFollower>()
        assertTrue(stepDowns.isNotEmpty(), "Expected BecomeFollower event after partition heal")
    }

    @Test fun trace_clocks_are_monotonic() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val clocks = mutableListOf<Long>()
        val job = launch { leader.trace.collect { clocks.add(it.clock) } }
        repeat(3) { leader.propose(byteArrayOf(it.toByte())) }
        delay(20)
        job.cancel()

        assertTrue(clocks.isNotEmpty(), "Expected trace events")
        for (i in 1 until clocks.size) {
            assertTrue(
                clocks[i] > clocks[i - 1],
                "Clock not monotonic at index $i: ${clocks[i - 1]} -> ${clocks[i]}",
            )
        }
    }

    @Test fun vote_events_emitted() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val allEvents = mutableListOf<RaftTraceEvent>()
        val collectJobs = sim.nodes.values.map { node ->
            launch { node.trace.collect { allEvents.add(it) } }
        }
        awaitLeader(sim)
        delay(20)
        collectJobs.forEach { it.cancel() }

        val granted = allEvents.filterIsInstance<RaftTraceEvent.VoteGranted>()
        assertTrue(granted.isNotEmpty(), "Expected at least one VoteGranted event")
    }

    @Test fun appendEntries_events_emitted() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val events = mutableListOf<RaftTraceEvent>()
        val job = launch { leader.trace.collect { events.add(it) } }
        delay(10) // let a heartbeat fire
        job.cancel()

        val appendEvents = events.filterIsInstance<RaftTraceEvent.AppendEntries>()
        assertTrue(appendEvents.isNotEmpty(), "Expected at least one AppendEntries trace event from leader")
    }
}
