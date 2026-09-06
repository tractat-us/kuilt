package us.tractat.kuilt.raft.pbt

import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Pure synchronous model check for Raft safety invariants
//
// NO kotlinx.coroutines. NO live engine. NO runTest. NO TestDispatcher.
// Every try is a sequence of pure Cluster → Cluster transformations that
// terminates in microseconds. Generation, seeding and shrinking live in
// [RaftActionSequences]; this file is the model's step function, its five
// invariants, and the properties that drive them.
// ---------------------------------------------------------------------------

/**
 * Fails the enclosing property if [condition] does not hold.
 *
 * Deliberately **not** `kotlin.assert`: on the JVM that compiles to a `-ea`-gated check, so the
 * entire invariant surface evaporates silently under any runner that does not enable assertions,
 * and the properties then pass by doing nothing. An unconditional throw makes the check a property
 * of the code rather than of the launcher, and behaves identically on every target.
 */
private inline fun invariant(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

class PureRaftModelTest {

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
            is RaftAction.Compact -> c.compact(nodes[action.nodeIdx.mod(nodes.size)], c.globalCommitFloor())
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
            invariant(leaders.size <= 1) {
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
                            invariant(pa.term == pb.term && pa.command.contentEquals(pb.command)) {
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
                    invariant(ea.term == eb.term && ea.command.contentEquals(eb.command)) {
                        "State Machine Safety violated at committed index $idx: " +
                            "${a.id} has (term=${ea.term}, cmd=${ea.command.contentToString()}), " +
                            "${b.id} has (term=${eb.term}, cmd=${eb.command.contentToString()})"
                    }
                }
            }
        }
    }

    /**
     * A committed entry, together with the term it was committed **in** — which is not the same thing
     * as [LogEntry.term], the term it was *created* in. §5.4.2 is exactly the rule that lets the two
     * diverge: a prior-term entry becomes committed by implication when a current-term entry commits
     * above it, so an entry created in term 1 is routinely committed in term 3.
     */
    private data class CommittedEntry(val entry: LogEntry, val committedInTerm: Long)

    /**
     * Leader Completeness (Ongaro, Fig. 3.2): *"if a log entry is committed in a given term, then that
     * entry will be present in the logs of the leaders for all higher-numbered terms."*
     *
     * **Keyed on the commit term, not the entry's own term.** Keying on `entry.term` states a strictly
     * stronger proposition, and that proposition is **not a theorem of Raft** — it flags a correct
     * trajectory. A stale leader is the witness: n2 wins term 2 with log `[1:t2]`, n1 goes on to lead
     * term 3 with `[1:t1, 2:t3]` and commits index 1 by implication under §5.4.2. Index 1 is now
     * committed *in term 3*, n2 is a leader for term 2, and 2 is not higher than 3 — so Raft promises
     * nothing about n2, which is right, because a stale leader that no quorum will answer can never
     * commit its conflicting entry. `entry.term` (1) is below n2's term (2), so the old spelling
     * reported a violation there. Reached unmutated at try 13 224 of a 20 000-trajectory sweep (#2114).
     *
     * [committedEntries] accumulates entries as they become committed anywhere in the cluster
     * (index → entry + commit term). It grows monotonically across steps.
     */
    private fun checkLeaderCompleteness(c: Cluster, committedEntries: Map<Long, CommittedEntry>) {
        val leaders = c.replicas.values.filter { it.alive && it.role == RaftRole.Leader }
        for (leader in leaders) {
            for ((idx, record) in committedEntries) {
                val committed = record.entry
                if (record.committedInTerm >= leader.term) continue
                // A compacted leader holds the entry in its snapshot baseline, not its retained log.
                if (idx <= leader.snapshotIndex) continue
                val entry = leader.entryAt(idx)
                invariant(
                    entry != null && entry.term == committed.term && entry.command.contentEquals(committed.command),
                ) {
                    "Leader Completeness violated: leader ${leader.id} (term=${leader.term}) " +
                        "is missing entry at index=$idx (term=${committed.term}, " +
                        "cmd=${committed.command.contentToString()}) committed in term ${record.committedInTerm}"
                }
            }
        }
    }

    /**
     * Compaction Completeness: compaction never discards a committed entry the snapshot doesn't cover.
     * For every alive replica, every committed index is either in the retained log or below the
     * snapshot baseline — so the node can always reconstruct its full committed state from
     * `snapshot ∪ retained-log`. Catches a bug where compaction discards past `commitIndex` or fails
     * to advance `snapshotIndex` when it drops the prefix.
     */
    private fun checkCompactionCompleteness(c: Cluster) {
        for (r in c.replicas.values) {
            if (!r.alive) continue
            for (idx in 1..r.commitIndex) {
                invariant(r.hasCommitted(idx)) {
                    "Compaction Completeness violated on ${r.id}: committed index $idx is neither in the " +
                        "retained log nor covered by the snapshot baseline (snapshotIndex=${r.snapshotIndex})"
                }
            }
        }
    }

    /**
     * Accumulates newly committed entries from the current cluster snapshot into
     * [committedEntries]. An entry at index [idx] is committed on a replica when
     * its [commitIndex] ≥ [idx].
     */
    private fun collectCommitted(c: Cluster, committedEntries: MutableMap<Long, CommittedEntry>) {
        for (replica in c.replicas.values) {
            if (!replica.alive) continue
            for (entry in replica.log) {
                if (entry.index > replica.commitIndex) continue
                // A replica holding index `i` committed while at term T witnesses "committed in some term
                // <= T". Keeping the LOWEST such witness ever seen is the tightest bound available, and
                // therefore the strictest sound key: a looser one would check fewer leaders.
                val previous = committedEntries[entry.index]
                committedEntries[entry.index] = CommittedEntry(
                    entry = previous?.entry ?: entry,
                    committedInTerm = minOf(previous?.committedInTerm ?: Long.MAX_VALUE, replica.term),
                )
            }
        }
    }

    private fun checkAllInvariants(c: Cluster, committedEntries: Map<Long, CommittedEntry>) = assertAll(
        { checkElectionSafety(c) },
        { checkLogMatching(c) },
        { checkStateMachineSafety(c) },
        { checkLeaderCompleteness(c, committedEntries) },
        { checkCompactionCompleteness(c) },
    )

    /** Replays one trajectory against a fresh cluster of [nodeIds], checking every invariant after each step. */
    private fun replay(nodeIds: Array<String>, actions: List<RaftAction>, maxEntriesPerAppend: Int? = null) {
        var c = cluster(*nodeIds, maxEntriesPerAppend = maxEntriesPerAppend)
        val nodes = c.replicas.keys.toList()
        val committedEntries = mutableMapOf<Long, CommittedEntry>()
        for (action in actions) {
            c = applyAction(c, action, nodes)
            collectCommitted(c, committedEntries)
            checkAllInvariants(c, committedEntries)
        }
    }

    // ── Properties ───────────────────────────────────────────────────────────

    @Test
    fun `safety invariants hold in a 3-node cluster`() = forAllActionSequences(
        property = "safety invariants hold in a 3-node cluster",
        tries = THREE_NODE_TRIES,
        maxActions = MAX_ACTIONS,
    ) { actions -> replay(arrayOf("n1", "n2", "n3"), actions) }

    @Test
    fun `safety invariants hold in a 5-node cluster`() = forAllActionSequences(
        property = "safety invariants hold in a 5-node cluster",
        tries = FIVE_NODE_TRIES,
        maxActions = MAX_ACTIONS,
    ) { actions -> replay(arrayOf("n1", "n2", "n3", "n4", "n5"), actions) }

    @Test
    fun `safety invariants hold in a 3-node cluster under bounded AppendEntries batches`() = forAllActionSequences(
        property = "safety invariants hold in a 3-node cluster under bounded AppendEntries batches",
        tries = BOUNDED_TRIES,
        maxActions = BOUNDED_MAX_ACTIONS,
        deliverWeight = BOUNDED_DELIVER_WEIGHT,
    ) { actions -> replay(arrayOf("n1", "n2", "n3"), actions, maxEntriesPerAppend = MAX_ENTRIES_PER_APPEND) }

    @Test
    fun `safety invariants hold in a 5-node cluster under bounded AppendEntries batches`() = forAllActionSequences(
        property = "safety invariants hold in a 5-node cluster under bounded AppendEntries batches",
        tries = BOUNDED_TRIES,
        maxActions = BOUNDED_MAX_ACTIONS,
        deliverWeight = BOUNDED_DELIVER_WEIGHT,
    ) { actions ->
        replay(arrayOf("n1", "n2", "n3", "n4", "n5"), actions, maxEntriesPerAppend = MAX_ENTRIES_PER_APPEND)
    }

    // ── Invariant self-checks ────────────────────────────────────────────────

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

        // committedEntries: the entry was committed in term 1 at index 1 — n2/n3 witness it while at term 1
        val committedEntries = mapOf(1L to CommittedEntry(committedEntry, committedInTerm = 1L))

        assertFailsWith<AssertionError> {
            checkLeaderCompleteness(c, committedEntries)
        }
    }

    /**
     * §5.3 rule 3 applies to **every** entry of a batch, not only the first.
     *
     * The rig is the one shape where "first" and "every" differ: `nextIndex` has backed all the way to
     * 1, so `prevLogIndex` is 0 and the consistency check is skipped entirely; the batch's first entry
     * then *matches* while the divergence sits one entry later. The pre-#1248 form kept the follower's
     * stale entry at that index and appended around it, and the follower went on to attest a log the
     * leader never sent.
     *
     * A directed test rather than a property: the random surface cannot see this. The unbounded
     * properties would need trajectories several times longer than they run (measured: 5 State Machine
     * Safety violations per 20 000 compaction-free 3-node trajectories of up to 200 actions), and the
     * bounded ones carry one entry per frame, where the two readings coincide by construction (#2114).
     */
    @Test
    fun `onAppendEntries truncates on a conflict later in the batch, not only the first entry`() {
        val n1 = NodeId("n1")
        val n2 = NodeId("n2")
        val shared = LogEntry(index = 1L, term = 1L, command = byteArrayOf())
        val leaderBatch = listOf(shared, LogEntry(index = 2L, term = 2L, command = byteArrayOf()))
        // The follower agrees at index 1 and DIVERGES at index 2 — the entry a term-1 leader gave it.
        val stale = LogEntry(index = 2L, term = 1L, command = byteArrayOf(9))
        val follower = Replica(id = n2, term = 2L, log = listOf(shared, stale))
        val leader = Replica(id = n1, term = 2L, role = RaftRole.Leader, log = leaderBatch)

        val before = Cluster(
            replicas = mapOf(n1 to leader, n2 to follower),
            voters = setOf(n1, n2),
            inFlight = listOf(
                ModelMsg.AppendEntries(
                    from = n1, to = n2, term = 2L,
                    prevLogIndex = 0L, prevLogTerm = 0L, entries = leaderBatch, leaderCommit = 0L,
                ),
            ),
        )
        val after = before.deliver(0)
        val settled = after.replicas.getValue(n2)
        val reply = after.inFlight.filterIsInstance<ModelMsg.AppendEntriesResp>().single()

        assertAll(
            // Precondition: the rig really does diverge only at the batch's SECOND entry, so a
            // first-entry-only check cannot notice it. Without this the test is green either way.
            { assertEquals(shared.term, before.replicas.getValue(n2).entryAt(1L)?.term, "index 1 must match") },
            { assertEquals(1L, before.replicas.getValue(n2).entryAt(2L)?.term, "index 2 must diverge") },
            { assertEquals(2L, leaderBatch.last().term, "…against the leader's term-2 entry") },
            { assertEquals(listOf(1L to 1L, 2L to 2L), settled.log.map { it.index to it.term }) },
            { assertTrue(settled.entryAt(2L)!!.command.isEmpty(), "stale command [9] must be gone") },
            { assertTrue(reply.success, "the frame is well-formed and must be accepted") },
            { assertEquals(2L, reply.matchIndex, "attests exactly what the batch covered") },
        )
    }

    /**
     * Leader Completeness says nothing about a leader whose term is **below** the term an entry was
     * committed in — and a stale leader in that position is an ordinary Raft state, not a violation.
     *
     * The mirror of the self-check above: that one proves the invariant still fires, this one proves it
     * no longer fires where Raft makes no promise. Keying on `entry.term` instead of the commit term
     * reported a violation here, and the random surface does not reach it at the shipped budget — it
     * took an unbounded 200-action trajectory at try 13 224 of 20 000 to surface (#2114).
     */
    @Test
    fun `checkLeaderCompleteness ignores a stale leader below the term the entry was committed in`() {
        val n1 = NodeId("n1")
        val n2 = NodeId("n2")
        val entry = LogEntry(index = 1L, term = 1L, command = byteArrayOf())
        // n2 won term 2 with its own conflicting entry, then n1 led term 3 and committed index 1 there.
        val staleLeader = Replica(
            id = n2, term = 2L, role = RaftRole.Leader,
            log = listOf(LogEntry(index = 1L, term = 2L, command = byteArrayOf())),
        )
        val currentLeader = Replica(
            id = n1, term = 3L, role = RaftRole.Leader, commitIndex = 2L,
            log = listOf(entry, LogEntry(index = 2L, term = 3L, command = byteArrayOf())),
        )
        val c = Cluster(replicas = mapOf(n1 to currentLeader, n2 to staleLeader), voters = setOf(n1, n2))
        val committedInTerm3 = mapOf(1L to CommittedEntry(entry, committedInTerm = 3L))

        assertAll(
            // Premise: n2 IS a leader, IS missing the entry, and its term IS above the entry's own term
            // — every condition the old spelling keyed on. Only the commit term separates the two.
            { assertEquals(RaftRole.Leader, staleLeader.role) },
            { assertTrue(staleLeader.entryAt(1L)!!.term != entry.term, "n2 must diverge at the index") },
            { assertTrue(staleLeader.term > entry.term, "…and outrank the entry's own term") },
            { assertTrue(staleLeader.term < 3L, "…while sitting below the commit term") },
            { checkLeaderCompleteness(c, committedInTerm3) },
        )
    }

    /**
     * Verifies checkCompactionCompleteness detects over-compaction: a node whose snapshot baseline
     * (index 3) leaves a hole at committed index 4 — dropped from the log but not covered by the snapshot.
     */
    @Test
    fun `checkCompactionCompleteness detects a committed entry lost to over-compaction`() {
        val n1 = NodeId("n1")
        val broken = Replica(
            id = n1,
            term = 2L,
            role = RaftRole.Leader,
            log = listOf(LogEntry(5L, 2L, byteArrayOf())), // index 4 missing from log
            commitIndex = 5L,
            snapshotIndex = 3L, // ...and not covered by the snapshot
            snapshotTerm = 1L,
            alive = true,
        )
        val c = Cluster(replicas = mapOf(n1 to broken), voters = setOf(n1))

        assertFailsWith<AssertionError> { checkCompactionCompleteness(c) }
    }

    /**
     * Compacting every node through the cluster-wide replicated floor discards the log prefix yet keeps
     * every committed index reconstructable (snapshot ∪ retained log), and all safety invariants hold —
     * including Leader Completeness for a higher-term leader whose prior-term committed entries now live
     * only in its snapshot.
     */
    @Test
    fun `compacting through the global floor preserves committed state and invariants`() {
        val ids = listOf("n1", "n2", "n3").map { NodeId(it) }
        val log = (1L..5L).map { LogEntry(it, 1L, byteArrayOf(it.toByte())) }
        // Converged: all three hold [1..5] committed; n1 later won term 2 (no new entries yet).
        var c = Cluster(
            replicas = ids.associateWith { Replica(id = it, term = 1L, log = log, commitIndex = 5L) } +
                (ids[0] to Replica(id = ids[0], term = 2L, role = RaftRole.Leader, log = log, commitIndex = 5L)),
            voters = ids.toSet(),
        )
        val floor = c.globalCommitFloor()
        for (id in ids) c = c.compact(id, floor)

        val committed = (1L..5L).associateWith { idx -> CommittedEntry(log.first { it.index == idx }, 1L) }
        assertAll(
            { assertEquals(5L, floor) },
            { c.replicas.values.forEach { r -> assertEquals(emptyList(), r.log, "${r.id} log fully compacted") } },
            { c.replicas.values.forEach { r -> (1L..r.commitIndex).forEach { assertTrue(r.hasCommitted(it)) } } },
            { checkCompactionCompleteness(c) },
            { checkLeaderCompleteness(c, committed) },
            { checkStateMachineSafety(c) },
        )
    }

    private companion object {
        /**
         * Trajectory budget. The model is pure and synchronous — a try costs microseconds on the JVM —
         * but these properties now also run interpreted under wasmJs, roughly an order of magnitude
         * slower, so the budget is sized for the slowest target rather than the fastest.
         */
        const val THREE_NODE_TRIES = 3_000
        const val FIVE_NODE_TRIES = 1_500

        /**
         * Upper bound on trajectory length. A safety violation needs an election, a replication round
         * and a second election before it can appear, so short trajectories are structurally incapable
         * of finding one — length is drawn uniformly up to this bound rather than biased small.
         */
        const val MAX_ACTIONS = 60

        /**
         * Entries one AppendEntries may carry in the bounded properties (see
         * [Cluster.maxEntriesPerAppend]). **1 is the engine's own floor, not a contrivance**:
         * `RaftEngine.boundedBatch` sends a single entry alone whenever nothing more fits the
         * transport's payload budget, which `ProposePayloadBudgetTest` exercises with real budgets.
         * 2 also reaches the §5.4.2 state, less often — see the measurement in the PR for #2114.
         */
        const val MAX_ENTRIES_PER_APPEND = 1

        /**
         * Budget for the two bounded properties. Bigger than [MAX_ACTIONS] / [THREE_NODE_TRIES]
         * because Figure 8 is a **three-election** shape — an entry replicated under one leader, a
         * second leader that inherits it uncommitted, and a third that can still overwrite it — and a
         * 60-action trajectory at the default delivery weight ends long before the third.
         *
         * Each figure was measured rather than picked (#2114). Under the §5.4.2 mutation, at
         * `maxEntriesPerAppend = 1`: 60 and 120 actions find nothing at any delivery weight, 200 finds
         * it at try 1 954, and 400 at try 1 747 with 27 counterexamples per 30 000 trajectories. The
         * 5-node arm needs 400 (first hit at try 1 668). Unmutated, 480 000 trajectories across the
         * whole grid produce zero.
         */
        const val BOUNDED_TRIES = 6_000
        const val BOUNDED_MAX_ACTIONS = 400
        const val BOUNDED_DELIVER_WEIGHT = 12
    }
}
