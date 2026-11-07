@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.test.Test
import kotlin.test.assertFalse
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
 * #3124: per-peer replication state a leader recorded in an earlier term must not survive into a
 * later one for a peer that was outside the configuration when it won.
 *
 * Before the fix the leader never removed a key from `nextIndex` or `matchIndex`, and `becomeLeader`
 * overwrote only the peers in the configuration at election time. A peer outside the config kept
 * whatever this node recorded for it the last time it led, and re-admitting the peer fed those values
 * back in. Both halves break something:
 * - **`nextIndex`** can sit above the leader's own log, because the log shrinks when a later leader
 *   overwrites this node's uncommitted suffix. The first AppendEntries to the re-added peer then has
 *   no `prevLogTerm` to send, and the leader's actor dies on `error("prevTerm for in-window index …")`.
 *   The node still reports Leader, and nothing commits again.
 * - **`matchIndex`** counts toward the new side of the joint quorum, so the Joint can commit without
 *   a new-config majority (§6 joint consensus, Leader Completeness).
 *
 * [enterTheWindow] builds the trajectory, with voters `{l, a, b}` and learner `d`:
 * 1. **T1.** `l` leads, partitioned with only `d`. It appends [UNCOMMITTED] entries and `d` acks
 *    them, so `nextIndex[d]` and `matchIndex[d]` are high. Nothing commits: `a` and `b` are cut off.
 * 2. **T2.** `a` or `b` leads and removes `d`. Healed, `l` has its uncommitted suffix overwritten;
 *    `d` stays isolated.
 * 3. **T3.** `l` leads again. `d` is outside the config, so its values survive the election.
 */
class StaleMatchIndexReAddTest {

    /** The leader at T3 and the index the re-adding Joint will take. */
    private class Window(val leader: RaftNode, val jointIndex: Long)

    private suspend fun TestScope.enterTheWindow(sim: RaftSimulation): Window {
        val first = sim.awaitLeader()
        if (first !== sim.nodes.getValue(l)) first.transferLeadership(l)
        sim.awaitRole(l, RaftRole.Leader)
        val t1 = sim.nodes.getValue(l)
        sim.awaitCommit(t1.commitIndex.value, on = listOf(l, a, b))

        // T1: l can reach only d. Its proposals append, d acks them, nothing commits.
        sim.partition(setOf(l, d), setOf(a, b))
        val base = sim.storages.getValue(l).entries(1L).last().index
        repeat(UNCOMMITTED) { i ->
            backgroundScope.launch { runCatchingCancellable { t1.propose(byteArrayOf(i.toByte())) } }
        }
        delay(4.milliseconds)
        val dHolds = sim.storages.getValue(d).entries(1L).lastOrNull()?.index ?: 0L
        assertTrue(dHolds >= base + UNCOMMITTED, "precondition: d must have acked l's T1 suffix (d holds $dHolds, want ${base + UNCOMMITTED})")

        // T2: the {a, b} side elects a leader and removes d. Then l rejoins and is overwritten.
        val t2 = sim.awaitLeader(among = setOf(a, b))
        t2.changeMembership(ClusterConfig(voters = setOf(l, a, b)))
        sim.heal()
        sim.partitionOff(d)
        sim.awaitCommit(t2.commitIndex.value, on = listOf(l, a, b))

        // T3: l leads again, with d outside the config.
        t2.transferLeadership(l)
        sim.awaitRole(l, RaftRole.Leader)
        val t3 = sim.nodes.getValue(l)
        val lastIndex = sim.storages.getValue(l).entries(1L).last().index
        sim.awaitCommit(lastIndex, on = listOf(l, a, b))
        val jointIndex = lastIndex + 1L
        assertTrue(jointIndex <= base + UNCOMMITTED, "precondition: d's stale values must sit above the Joint's index $jointIndex")
        return Window(t3, jointIndex)
    }

    @Test
    fun reAddingAPeerRemovedInAnEarlierTermReplicatesToItAndCommits() = raftRunTest {
        val sim = staleMatchSim()
        val window = enterTheWindow(sim)
        sim.heal()

        // Re-add d as a voter with everyone reachable. With a stale nextIndex[d] above l's log, the
        // first AppendEntries to d killed l's actor and this never committed.
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(l, a, b, d)))
        sim.awaitCommit(window.jointIndex + 1L, on = listOf(l, a, b, d))
    }

    @Test
    fun staleMatchIndexFromAnEarlierTermNeverCountsTowardAJointCommit() = raftRunTest {
        val sim = staleMatchSim()
        val window = enterTheWindow(sim)
        sim.crash(b)

        // Re-add d as a voter. Only l and a can hold the Joint: 2 of the new side's 4.
        val change = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { window.leader.changeMembership(ClusterConfig(voters = setOf(l, a, b, d))) }
        }
        delay(WINDOW)
        // Precondition: the change was accepted, so a low commit below is a refusal to count d, not a change
        // that never started. It does not stay pending: under the Joint, CheckQuorum needs a new-side
        // majority of contacts too, so l steps down and the change fails with LeadershipLostException.
        val joint = sim.storages.getValue(l).entries(window.jointIndex).firstOrNull()
        assertTrue(joint?.config?.old != null, "precondition: l's log must hold the re-adding Joint at ${window.jointIndex}, found $joint")
        val outcome = if (change.isCompleted) change.await() else null
        assertFalse(outcome?.isSuccess == true, "the re-adding change completed with only l and a holding it: $outcome")
        val lCommit = window.leader.commitIndex.value
        val aCommit = sim.nodes.getValue(a).commitIndex.value
        assertTrue(
            lCommit < window.jointIndex && aCommit < window.jointIndex,
            "the Joint at ${window.jointIndex} committed with only l and a holding it, so d's stale matchIndex " +
                "was counted. commit l=$lCommit a=$aCommit",
        )

        // Positive control: once d can genuinely replicate it, the Joint commits. That is what makes the
        // assertion above discriminate — the rig commits as soon as a real new-side majority exists.
        sim.heal()
        sim.awaitCommit(window.jointIndex, on = listOf(l, a, d))
        change.cancel()
    }

    private companion object {
        /** How many entries `l` appends in T1: enough that `d`'s stale values clear the T3 Joint's index. */
        const val UNCOMMITTED = 20

        /** Many election timeouts and heartbeats of [fastRaftConfig]: ample for a stale-value commit to happen if it can. */
        val WINDOW = 200.milliseconds
    }
}
