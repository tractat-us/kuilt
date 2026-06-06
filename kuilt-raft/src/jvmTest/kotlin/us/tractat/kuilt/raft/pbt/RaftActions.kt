@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.pbt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.state.Action
import net.jqwik.api.state.Transformer
import us.tractat.kuilt.raft.NodeId

/**
 * jqwik [Action] implementations for Raft stateful property tests.
 *
 * Each action drives virtual time via [RaftModel.scheduler] so there are zero
 * real-clock waits. [runBlocking] is used as a thin bridge for non-delaying
 * suspend calls (invariant checks); all scheduling uses [RaftModel.advanceTimeBy]
 * and [RaftModel.advanceUntilIdle] which advance virtual time instantly.
 */

private fun <S> just(block: (S) -> S): Arbitrary<Transformer<S>> =
    Arbitraries.just(Transformer { s -> block(s) })

// checkInvariants() reads in-memory state only — no real suspension occurs.
// runBlocking without dispatcher context runs in its own event loop, never blocking
// the test scheduler.
// Skipped while a partition is active: an old-term minority leader + new-term majority
// leader can coexist during a partition without violating Raft's per-term safety guarantee.
private fun RaftModel.checkInvariants() {
    if (!partitioned) runBlocking { sim.checkInvariants() }
}

/**
 * Attempt to elect a leader. Advances virtual time; no-op if the cluster cannot elect
 * (e.g. active partition, majority crashed) — the property still passes because having
 * no leader is a valid Raft state. Invariants are checked only when a leader actually
 * exists to avoid false failures from transient leaderless states.
 */
internal object ElectLeaderAction : Action.Independent<RaftModel> {
    override fun transformer(): Arbitrary<Transformer<RaftModel>> = just { model ->
        val leader = model.advanceUntilLeader()
        model.advanceUntilIdle()
        if (leader != null) model.checkInvariants()
        model
    }

    override fun toString() = "ElectLeader"
}

/** Crash the current leader; advance virtual time so the remaining nodes can re-elect. */
internal object CrashLeaderAction : Action.Dependent<RaftModel> {
    override fun precondition(model: RaftModel) = model.sim.leader() != null

    override fun transformer(model: RaftModel): Arbitrary<Transformer<RaftModel>> = just { m ->
        val leader = m.sim.leader() ?: return@just m
        val leaderId = m.sim.nodes.entries.first { it.value === leader }.key
        m.sim.crash(leaderId)
        m.advanceTimeBy(PBT_CONFIG.electionTimeoutMax.inWholeMilliseconds + 30)
        m.advanceUntilIdle()
        m.checkInvariants()
        m
    }

    override fun toString() = "CrashLeader"
}

/**
 * Restart the lowest-numbered crashed node.
 * Only applicable when at least one node is not running.
 */
internal class RestartNodeAction(private val clusterSize: Int) : Action.Independent<RaftModel> {
    private fun crashedIds(model: RaftModel): List<NodeId> =
        (1..clusterSize).map { NodeId("n$it") }.filter { it !in model.sim.nodes }

    override fun precondition(model: RaftModel) = crashedIds(model).isNotEmpty()

    override fun transformer(): Arbitrary<Transformer<RaftModel>> = just { model ->
        val id = crashedIds(model).firstOrNull() ?: return@just model
        model.sim.restart(id)
        model.advanceTimeBy(PBT_CONFIG.electionTimeoutMax.inWholeMilliseconds)
        model.advanceUntilIdle()
        model.checkInvariants()
        model
    }

    override fun toString() = "RestartNode"
}

/**
 * Propose a command on the current leader.
 *
 * Launches the suspending [propose] on the test dispatcher, then advances virtual
 * time through enough heartbeat intervals for the quorum to commit. No real-clock
 * waits — all advancement is virtual.
 */
internal class ProposeAction(private val command: ByteArray) : Action.Dependent<RaftModel> {
    override fun precondition(model: RaftModel) = model.sim.leader() != null

    override fun transformer(model: RaftModel): Arbitrary<Transformer<RaftModel>> = just { m ->
        val leader = m.sim.leader() ?: return@just m
        // Launch propose on the test dispatcher. It is queued but not yet executed.
        CoroutineScope(m.dispatcher).launch {
            try { leader.propose(command) } catch (_: Exception) {}
        }
        // Drive time forward: each advanceTimeBy triggers coroutines pending at that tick,
        // including heartbeats and AppendEntries replies that constitute quorum agreement.
        repeat(5) { m.advanceTimeBy(PBT_CONFIG.heartbeatInterval.inWholeMilliseconds) }
        m.advanceUntilIdle()
        m.checkInvariants()
        m
    }

    override fun toString() = "Propose(${command.size}B)"
}

/**
 * Partition the cluster into two halves; advance time so the majority can elect a leader.
 *
 * Sets [RaftModel.partitioned] = true so subsequent invariant checks skip the
 * at-most-one-leader assertion: an old-term minority leader + new-term majority leader
 * can coexist during an active partition without violating Raft's per-term safety guarantee.
 */
internal object PartitionAction : Action.Dependent<RaftModel> {
    override fun precondition(model: RaftModel) = model.sim.nodes.size >= 3

    override fun transformer(model: RaftModel): Arbitrary<Transformer<RaftModel>> = just { m ->
        val ids = m.sim.nodes.keys.toList()
        val half = ids.size / 2
        m.sim.partition(ids.take(half).toSet(), ids.drop(half).toSet())
        m.partitioned = true
        m.advanceTimeBy(PBT_CONFIG.electionTimeoutMax.inWholeMilliseconds * 2)
        m.advanceUntilIdle()
        m
    }

    override fun toString() = "Partition"
}

/** Heal any active network partition and verify the cluster converges to a single leader. */
internal object HealAction : Action.Independent<RaftModel> {
    override fun transformer(): Arbitrary<Transformer<RaftModel>> = just { model ->
        model.sim.heal()
        model.partitioned = false
        model.advanceTimeBy(PBT_CONFIG.electionTimeoutMax.inWholeMilliseconds + 30)
        model.advanceUntilIdle()
        model.checkInvariants()
        model
    }

    override fun toString() = "Heal"
}
