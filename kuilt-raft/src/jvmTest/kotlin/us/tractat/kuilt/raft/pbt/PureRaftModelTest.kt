package us.tractat.kuilt.raft.pbt

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole

// ---------------------------------------------------------------------------
// Pure synchronous PBT for Raft safety invariants
//
// NO kotlinx.coroutines. NO live engine. NO runTest. NO TestDispatcher.
// Every jqwik try is a sequence of pure Cluster → Cluster transformations
// that terminates in microseconds.
// ---------------------------------------------------------------------------

/** Actions that can be applied to a Cluster snapshot. File-level so jqwik can see the type. */
public sealed interface RaftAction {
    /** Drive node[nodeIdx % clusterSize] to start an election. */
    data class Timeout(val nodeIdx: Int) : RaftAction
    /** Deliver inFlight[msgIdx % queue.size] (or no-op if queue is empty). */
    data class Deliver(val msgIdx: Int) : RaftAction
    /** Propose next command byte to the current leader (no-op if no leader). */
    data object Propose : RaftAction
    /** Crash node[nodeIdx % clusterSize]. */
    data class Crash(val nodeIdx: Int) : RaftAction
    /** Restart (dead) node[nodeIdx % clusterSize]. */
    data class Restart(val nodeIdx: Int) : RaftAction
    /** Partition node[aIdx] from node[bIdx]. */
    data class Partition(val aIdx: Int, val bIdx: Int) : RaftAction
    /** Heal all partitions. */
    data object Heal : RaftAction
}

class PureRaftModelTest {

    // ── Arbitraries ─────────────────────────────────────────────────────────

    @Provide
    fun actions(): Arbitrary<List<RaftAction>> {
        val timeout = Arbitraries.integers().between(0, 10).map { RaftAction.Timeout(it) }
        val deliver = Arbitraries.integers().between(0, 100).map { RaftAction.Deliver(it) }
        val propose = Arbitraries.just<RaftAction>(RaftAction.Propose)
        val crash = Arbitraries.integers().between(0, 10).map { RaftAction.Crash(it) }
        val restart = Arbitraries.integers().between(0, 10).map { RaftAction.Restart(it) }
        val partition = Arbitraries.integers().between(0, 10).flatMap { a ->
            Arbitraries.integers().between(0, 10).map<RaftAction> { b -> RaftAction.Partition(a, b) }
        }
        val heal = Arbitraries.just<RaftAction>(RaftAction.Heal)

        // Deliver weighted 3x — messages need to be processed to make progress
        val oneAction: Arbitrary<RaftAction> = Arbitraries.oneOf(
            timeout, deliver, deliver, deliver,
            propose, crash, restart, partition, heal,
        )
        return oneAction.list().ofMinSize(1).ofMaxSize(40)
    }

    // ── Step function ────────────────────────────────────────────────────────

    private fun applyAction(c: Cluster, action: RaftAction, nodes: List<NodeId>): Cluster {
        if (nodes.isEmpty()) return c
        return when (action) {
            is RaftAction.Timeout -> c.timeout(nodes[action.nodeIdx.mod(nodes.size)])
            is RaftAction.Deliver -> c.deliver(action.msgIdx)
            is RaftAction.Propose -> c.propose(c.nextCommandByte)
            is RaftAction.Crash -> c.crash(nodes[action.nodeIdx.mod(nodes.size)])
            is RaftAction.Restart -> c.restart(nodes[action.nodeIdx.mod(nodes.size)])
            is RaftAction.Partition -> {
                val a = nodes[action.aIdx.mod(nodes.size)]
                val b = nodes[action.bIdx.mod(nodes.size)]
                if (a == b) c else c.partition(a, b)
            }
            is RaftAction.Heal -> c.healAll()
        }
    }

    // ── Invariant checks ─────────────────────────────────────────────────────

    /**
     * Election Safety: at most one leader per term across all alive replicas.
     */
    private fun checkElectionSafety(c: Cluster) {
        val leadersByTerm = c.replicas.values
            .filter { it.alive && it.role == RaftRole.Leader }
            .groupBy { it.term }

        leadersByTerm.forEach { (term, leaders) ->
            assert(leaders.size <= 1) {
                "Election Safety violated: ${leaders.size} leaders in term $term: ${leaders.map { it.id }}"
            }
        }
    }

    /**
     * Log Matching: if two replicas share the same (index, term) at some position,
     * all preceding entries are identical.
     */
    private fun checkLogMatching(c: Cluster) {
        val aliveReplicas = c.replicas.values.filter { it.alive }
        for (i in aliveReplicas.indices) {
            for (j in (i + 1) until aliveReplicas.size) {
                val a = aliveReplicas[i]
                val b = aliveReplicas[j]
                val sharedIndices = a.log.map { it.index }.toSet()
                    .intersect(b.log.map { it.index }.toSet())
                for (idx in sharedIndices) {
                    val ea = a.entryAt(idx) ?: continue
                    val eb = b.entryAt(idx) ?: continue
                    if (ea.term == eb.term) {
                        val prefixIndices = sharedIndices.filter { it < idx }
                        for (pi in prefixIndices) {
                            val pa = a.entryAt(pi) ?: continue
                            val pb = b.entryAt(pi) ?: continue
                            assert(pa.term == pb.term && pa.command.contentEquals(pb.command)) {
                                "Log Matching violated at index $pi between ${a.id} and ${b.id}: " +
                                    "matched at ($idx,${ea.term}) but prefix diverges"
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * State Machine Safety: no two alive replicas have a different committed entry at the same index.
     */
    private fun checkStateMachineSafety(c: Cluster) {
        val aliveReplicas = c.replicas.values.filter { it.alive }
        for (i in aliveReplicas.indices) {
            for (j in (i + 1) until aliveReplicas.size) {
                val a = aliveReplicas[i]
                val b = aliveReplicas[j]
                val minCommit = minOf(a.commitIndex, b.commitIndex)
                for (idx in 1..minCommit) {
                    val ea = a.entryAt(idx) ?: continue
                    val eb = b.entryAt(idx) ?: continue
                    assert(ea.term == eb.term && ea.command.contentEquals(eb.command)) {
                        "State Machine Safety violated at committed index $idx: " +
                            "${a.id} has (term=${ea.term}, cmd=${ea.command.contentToString()}), " +
                            "${b.id} has (term=${eb.term}, cmd=${eb.command.contentToString()})"
                    }
                }
            }
        }
    }

    /**
     * Leader Completeness: if an entry was committed in term T, every leader
     * whose term is strictly greater than T must have that entry in its log.
     *
     * [committedEntries] accumulates entries as they become committed anywhere
     * in the cluster (index → entry). It grows monotonically across steps.
     */
    private fun checkLeaderCompleteness(c: Cluster, committedEntries: Map<Long, LogEntry>) {
        val leaders = c.replicas.values.filter { it.alive && it.role == RaftRole.Leader }
        for (leader in leaders) {
            for ((idx, committed) in committedEntries) {
                if (committed.term >= leader.term) continue
                val entry = leader.entryAt(idx)
                assert(entry != null && entry.term == committed.term && entry.command.contentEquals(committed.command)) {
                    "Leader Completeness violated: leader ${leader.id} (term=${leader.term}) " +
                        "is missing committed entry at index=$idx (term=${committed.term}, " +
                        "cmd=${committed.command.contentToString()})"
                }
            }
        }
    }

    /**
     * Accumulates newly committed entries from the current cluster snapshot into
     * [committedEntries]. An entry at index [idx] is committed on a replica when
     * its [commitIndex] ≥ [idx].
     */
    private fun collectCommitted(c: Cluster, committedEntries: MutableMap<Long, LogEntry>) {
        for (replica in c.replicas.values) {
            if (!replica.alive) continue
            for (entry in replica.log) {
                if (entry.index <= replica.commitIndex && entry.index !in committedEntries) {
                    committedEntries[entry.index] = entry
                }
            }
        }
    }

    private fun checkAllInvariants(c: Cluster, committedEntries: Map<Long, LogEntry>) = assertAll(
        { checkElectionSafety(c) },
        { checkLogMatching(c) },
        { checkStateMachineSafety(c) },
        { checkLeaderCompleteness(c, committedEntries) },
    )

    // ── Properties ───────────────────────────────────────────────────────────

    @Property(tries = 500)
    fun `safety invariants hold in a 3-node cluster`(
        @ForAll("actions") actions: List<RaftAction>,
    ): Boolean {
        var c = cluster("n1", "n2", "n3")
        val nodes = c.replicas.keys.toList()
        val committedEntries = mutableMapOf<Long, LogEntry>()
        for (action in actions) {
            c = applyAction(c, action, nodes)
            collectCommitted(c, committedEntries)
            checkAllInvariants(c, committedEntries)
        }
        return true
    }

    @Property(tries = 300)
    fun `safety invariants hold in a 5-node cluster`(
        @ForAll("actions") actions: List<RaftAction>,
    ): Boolean {
        var c = cluster("n1", "n2", "n3", "n4", "n5")
        val nodes = c.replicas.keys.toList()
        val committedEntries = mutableMapOf<Long, LogEntry>()
        for (action in actions) {
            c = applyAction(c, action, nodes)
            collectCommitted(c, committedEntries)
            checkAllInvariants(c, committedEntries)
        }
        return true
    }

    /**
     * Verifies checkLeaderCompleteness detects a violation.
     *
     * Constructs a cluster state where a committed entry (index=1, term=1) exists but
     * the only live leader is in term 2 and is missing that entry from its log — the
     * kind of corruption that bypassing log-upToDate in election would allow.
     */
    @Test
    fun `checkLeaderCompleteness detects a leader missing a committed prior-term entry`() {
        val n1 = NodeId("n1")
        val n2 = NodeId("n2")
        val n3 = NodeId("n3")

        val committedEntry = LogEntry(index = 1L, term = 1L, command = byteArrayOf(42))

        // n1: leader in term 2, missing the committed entry from term 1
        val staleLeader = Replica(
            id = n1,
            term = 2L,
            role = RaftRole.Leader,
            log = listOf(LogEntry(index = 2L, term = 2L, command = byteArrayOf())),
            commitIndex = 0L,
            alive = true,
        )
        // n2, n3: followers that have the committed entry
        val follower2 = Replica(id = n2, term = 1L, log = listOf(committedEntry), commitIndex = 1L, alive = true)
        val follower3 = Replica(id = n3, term = 1L, log = listOf(committedEntry), commitIndex = 1L, alive = true)

        val c = Cluster(
            replicas = mapOf(n1 to staleLeader, n2 to follower2, n3 to follower3),
            voters = setOf(n1, n2, n3),
        )

        // committedEntries: the entry was committed in term 1 at index 1
        val committedEntries = mapOf(1L to committedEntry)

        assertThrows(AssertionError::class.java) {
            checkLeaderCompleteness(c, committedEntries)
        }
    }
}
