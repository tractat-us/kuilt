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
