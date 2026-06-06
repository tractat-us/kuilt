@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElectionTest {
    @Test fun initialElection_elects_exactly_one_leader() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        assertNotNull(leader)
        assertTrue(leader.role.value is RaftRole.Leader)
        sim.checkInvariants()
    }

    @Test fun reElection_after_leader_crash() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        sim.crash(leaderId)
        delay(50)
        val newLeader = awaitLeader(sim)
        assertNotNull(newLeader)
        assertTrue(newLeader !== leader)
        sim.checkInvariants()
    }

    @Test fun manyElections_invariants_hold() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
        repeat(5) {
            val leader = awaitLeader(sim)
            val id = sim.nodes.entries.first { it.value === leader }.key
            sim.crash(id)
            delay(20)
            sim.checkInvariants()
            sim.restart(id)
            delay(10)
        }
    }
}
