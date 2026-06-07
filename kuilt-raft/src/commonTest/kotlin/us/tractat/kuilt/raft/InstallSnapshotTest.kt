@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallSnapshotTest {

    /**
     * Headline scenario (#114): a node offline across a compaction boundary rejoins via
     * InstallSnapshot — the leader no longer holds entries at the node's prevLogIndex, so
     * AppendEntries alone can never catch it up.
     *
     * "Offline" is modelled as crash + restart ([RaftSimulation.crash]/[restart]) — the node's
     * scope is cancelled, so its election timer never fires and its term does NOT inflate. A
     * partition-while-running model would inflate the term and trigger the orthogonal
     * disruptive-rejoin problem (PreVote, #193), which is not what this test exercises.
     */
    @Test
    fun offlineFollower_rejoinsViaInstallSnapshot_afterCompaction() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.crash(offline)                               // truly offline — no term inflation
        repeat(20) { leader.propose(byteArrayOf(it.toByte())) }   // commit via the surviving quorum
        val finalCommit = leader.commitIndex.value
        val through = sim.compactionFloorCandidate(leaderId)      // a committed index past where the node left off

        leader.snapshots.value = Snapshot(through, sim.stateBytes(leaderId, through))
        leader.compactionFloor.first { it == through }   // leader compacts past the node's needed prefix

        sim.restart(offline)                             // back online, fresh from its (empty) persisted storage
        val installs = sim.collectInstalls(offline)
        sim.awaitCommit(finalCommit, on = setOf(offline))        // catches up — only possible via InstallSnapshot

        assertTrue(installs.isNotEmpty(), "rejoined node must receive a Committed.Install")
        assertEquals(through, installs.last().snapshot.throughIndex)
        assertContentEquals(
            sim.appliedState(leaderId), sim.appliedState(offline),
            "rejoined node's state machine must converge with the leader's",
        )
    }

    /** A small snapshot still spans many chunks when the transport reports a tiny [maxPayloadBytes]. */
    @Test
    fun chunkedTransfer_reassemblesUnderTinyMaxPayload() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 64)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.crash(offline)
        repeat(10) { leader.propose(byteArrayOf(8)) }
        val finalCommit = leader.commitIndex.value
        val through = sim.compactionFloorCandidate(leaderId)

        val bigState = ByteArray(1000) { it.toByte() }   // ~16 chunks at 64 B
        leader.snapshots.value = Snapshot(through, bigState)
        leader.compactionFloor.first { it == through }

        sim.restart(offline)
        val installs = sim.collectInstalls(offline)
        sim.awaitCommit(finalCommit, on = setOf(offline))

        assertEquals(through, installs.last().snapshot.throughIndex)
        assertEquals(1000, installs.last().snapshot.state.size, "all chunks reassembled in order")
        assertContentEquals(bigState, installs.last().snapshot.state, "bytes reassembled in order")
    }
}
