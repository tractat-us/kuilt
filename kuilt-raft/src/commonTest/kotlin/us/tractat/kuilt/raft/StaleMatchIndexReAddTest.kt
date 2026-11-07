@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val l = NodeId("l")
private val a = NodeId("a")
private val b = NodeId("b")
private val d = NodeId("d")

private fun TestScope.staleMatchSim(): RaftSimulation {
    val config = fastRaftConfig()
    return RaftSimulation(
        nodeIds = listOf(l, a, b, d),
        scope = this,
        nodeScope = backgroundScope,
        nodeFactory = { _, transport, storage, nodeScope ->
            nodeScope.raftNode(ClusterConfig(voters = setOf(l, a, b), learners = setOf(d)), transport, storage, config)
        },
    )
}

/**
 * #3124: a `matchIndex` a leader recorded in an earlier term must never count toward a commit in a
 * later one.
 *
 * Before the fix the leader never removed a key from `matchIndex`, and `becomeLeader` overwrote only
 * the peers in the configuration at election time. So a peer that was outside the config when this
 * node won kept whatever value this node recorded for it the last time it led. Re-admitting that peer
 * as a voter put the stale value straight into the new side of the joint quorum.
 *
 * The trajectory, with voters `{l, a, b}` and learner `d`:
 * 1. **T1.** `l` leads, partitioned with only `d`. It appends [UNCOMMITTED] entries and `d` acks
 *    them, so `matchIndex[d]` is high. Nothing commits: `a` and `b` are unreachable.
 * 2. **T2.** `a` or `b` leads, removes `d`, and overwrites `l`'s uncommitted suffix once healed.
 * 3. **T3.** `l` leads again. `d` is outside the config, so its stale value survives election.
 * 4. With `b` crashed and `d` still isolated, `l` re-adds `d` as a voter:
 *    `Joint(old = {l,a,b}, new = {l,a,b,d})`. The old side has a majority (`l`, `a`). The new side
 *    needs 3 of 4, and only `l` and `a` hold the entry — but `d`'s stale value is above the Joint's
 *    index, so the leader counted it and committed the Joint without a new-config majority (§6
 *    joint consensus; Leader Completeness).
 *
 * The control changes the signal, not only the verdict: once `d` is healed it genuinely replicates
 * the Joint and the commit happens, so the rig is live and the window was entered.
 */
class StaleMatchIndexReAddTest {

    @Test
    fun staleMatchIndexFromAnEarlierTermNeverCountsTowardAJointCommit() = raftRunTest {
        val sim = staleMatchSim()
        val first = sim.awaitLeader()
        if (first !== sim.nodes.getValue(l)) first.transferLeadership(l)
        sim.awaitRole(l, RaftRole.Leader)
        val leaderL = sim.nodes.getValue(l)
        sim.awaitCommit(leaderL.commitIndex.value, on = listOf(l, a, b))

        // T1: l can reach only d. Its proposals append, d acks them, nothing commits.
        sim.partition(setOf(l, d), setOf(a, b))
        val base = sim.storages.getValue(l).entries(1L).last().index
        repeat(UNCOMMITTED) { i ->
            backgroundScope.launch { runCatching { leaderL.propose(byteArrayOf(i.toByte())) } }
        }
        delay(4.milliseconds)
        val dHolds = sim.storages.getValue(d).entries(1L).lastOrNull()?.index ?: 0L
        assertTrue(dHolds >= base + UNCOMMITTED, "precondition: d must have acked l's T1 suffix (d holds $dHolds, want ${base + UNCOMMITTED})")

        // T2: the {a, b} side elects a leader and removes d. Then l rejoins and is overwritten; d stays cut off.
        val t2 = sim.awaitLeader(among = setOf(a, b))
        t2.changeMembership(ClusterConfig(voters = setOf(l, a, b)))
        sim.heal()
        sim.partitionOff(d)
        val removalCommit = t2.commitIndex.value
        sim.awaitCommit(removalCommit, on = listOf(l, a, b))

        // T3: l leads again, then b crashes.
        t2.transferLeadership(l)
        sim.awaitRole(l, RaftRole.Leader)
        val t3 = sim.nodes.getValue(l)
        sim.awaitCommit(sim.storages.getValue(l).entries(1L).last().index, on = listOf(l, a, b))
        sim.crash(b)

        // Re-add d as a voter. Only l and a can hold the Joint, which is 2 of the new side's 4.
        val jointIndex = sim.storages.getValue(l).entries(1L).last().index + 1L
        assertTrue(jointIndex <= base + UNCOMMITTED, "precondition: d's stale matchIndex must sit above the Joint's index $jointIndex")
        val change = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { t3.changeMembership(ClusterConfig(voters = setOf(l, a, b, d))) }
        }
        delay(WINDOW)
        assertTrue(
            t3.commitIndex.value < jointIndex && sim.nodes.getValue(a).commitIndex.value < jointIndex,
            "the Joint at $jointIndex committed with only l and a holding it: d's stale matchIndex was counted. " +
                "commit l=${t3.commitIndex.value} a=${sim.nodes.getValue(a).commitIndex.value}",
        )

        // Positive control: once d can genuinely replicate it, the Joint commits.
        sim.heal()
        sim.awaitCommit(jointIndex, on = listOf(l, a, d))
        change.cancel()
    }

    private companion object {
        /** How many entries `l` appends in T1 — enough that `d`'s stale value clears the T3 Joint's index. */
        const val UNCOMMITTED = 20

        /** Many election timeouts and heartbeats of [fastRaftConfig]: ample for the stale-value commit to happen if it can. */
        val WINDOW = 200.milliseconds
    }
}
