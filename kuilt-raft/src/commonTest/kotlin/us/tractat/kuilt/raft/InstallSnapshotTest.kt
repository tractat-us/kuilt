@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallSnapshotTest {

    /**
     * Headline repro (#114): a follower offline across a compaction boundary must rejoin via
     * InstallSnapshot — the leader no longer holds entries at the follower's prevLogIndex, so
     * AppendEntries alone can never catch it up.
     */
    @Test
    fun offlineFollower_rejoinsViaInstallSnapshot_afterCompaction() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.partitionOff(offline)                         // c goes offline
        repeat(20) { leader.propose(byteArrayOf(it.toByte())) }
        val through = sim.compactionFloorCandidate(leaderId)   // a committed, live index past where c left off

        // the leader's consumer snapshots through `through` and raft compacts past where c left off
        leader.snapshots.value = Snapshot(through, sim.stateBytes(leaderId, through))
        leader.compactionFloor.first { it == through }

        // c rejoins — it cannot be caught up by AppendEntries (its needed prefix is gone)
        val installs = sim.collectInstalls(offline)
        val finalIndex = leader.commitIndex.value
        sim.heal()
        sim.nodes.getValue(offline).commitIndex.first { it >= finalIndex }

        assertTrue(installs.isNotEmpty(), "c must receive a Committed.Install")
        assertEquals(through, installs.last().snapshot.throughIndex)
        assertContentEquals(
            sim.appliedState(leaderId), sim.appliedState(offline),
            "c's state machine matches the leader's",
        )
    }

    @Test
    fun chunkedTransfer_reassemblesUnderTinyMaxPayload() = runTest(UnconfinedTestDispatcher()) {
        // transport reports a tiny maxPayloadBytes so a small snapshot still spans many chunks
        val sim = raftSim(this, backgroundScope, maxPayloadBytes = 64)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.partitionOff(offline)
        repeat(10) { leader.propose(byteArrayOf(8)) }
        val through = sim.compactionFloorCandidate(leaderId)

        val bigState = ByteArray(1000) { it.toByte() }          // ~16 chunks at 64B
        leader.snapshots.value = Snapshot(through, bigState)
        leader.compactionFloor.first { it == through }

        val installs = sim.collectInstalls(offline)
        val finalIndex = leader.commitIndex.value
        sim.heal()
        sim.nodes.getValue(offline).commitIndex.first { it >= finalIndex }

        assertEquals(through, installs.last().snapshot.throughIndex)
        assertEquals(1000, installs.last().snapshot.state.size, "all chunks reassembled in order")
        assertContentEquals(bigState, installs.last().snapshot.state, "bytes reassembled in order")
    }
}
