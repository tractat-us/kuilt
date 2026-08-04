@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * §5.4.2 / Figure 8 — the **commit restriction**: a leader may never advance `commitIndex` onto an
 * entry from an *earlier* term by counting replicas. Such an entry is still overwritable by a future
 * leader; it becomes committed only *by implication*, when an entry from the leader's own term
 * commits above it (Log Matching then covers the whole prefix).
 *
 * In `RaftEngine.tryAdvanceLeaderCommit` that restriction is exactly one conjunct —
 * `entry.term == state.currentTerm`. `MembershipState.committedIndex` is quorum arithmetic only
 * (Simple vs Joint, self-credit per voter set); it is handed a `matchIndex` map, a leader last-index
 * and a `NodeId`, and sees no term and no log, so it cannot enforce this and never has.
 *
 * ## Why this test asserts a NEGATIVE, and why an eventual-commit test would be worse than nothing
 *
 * The conjunct only ever *withholds* a commit. Deleting it therefore **enlarges** the set of
 * trajectories that commit, so every "…and eventually index N commits" assertion in this module
 * still passes without it — the whole `:kuilt-raft` suite stayed green under the deletion (#2021).
 * Adding one more eventual-commit test reproduces that blind spot rather than closing it.
 *
 * The pin has to be a negative *at a moment*: a prior-term entry replicated to a **voter quorum**
 * and still not committed at that instant, becoming committed only once a current-term entry
 * reaches quorum.
 *
 * ## The trajectory
 *
 * 1. A real term-1 leader's `AppendEntries` is hand-delivered to two of the three voters, carrying
 *    one entry at `leaderCommit = 0`. Both hold it **uncommitted**; the third voter never sees it,
 *    so §5.4.1 keeps it out of the leadership that follows.
 * 2. Those two elect a leader in a *later* term. It appends its own no-op above the inherited
 *    prior-term entry — the ordinary post-election catch-up shape, not a corner case.
 * 3. Every link carries [linkLatency], so for `2 × linkLatency` after the election the new leader
 *    has sent its first `AppendEntries` but no honest ack can yet have come back. The probe runs
 *    inside that window, and asserts it did (`elapsed < 2 × linkLatency`) so the commit in phase 2
 *    is attributable to the injected ack and not to honest replication.
 * 4. **Phase 1 (the pin).** One `AppendEntriesResponse` — a frame a real follower emits when it has
 *    replicated exactly that far — puts the *prior-term* index at a voter quorum. `commitIndex` must
 *    not move.
 * 5. **Phase 2 (the control, and it is not optional).** The identical injection path with the same
 *    virtual-time budget, naming the *current-term* no-op index, must commit. Without it phase 1
 *    would pass equally well against an engine that ignored injected responses altogether, and the
 *    test would assert nothing. The two phases differ in exactly one input — the term of the entry
 *    at the quorum index — so the discrimination is the guard's and nothing else's.
 *
 * Mutation-verified: deleting `entry.term == state.currentTerm` makes phase 1 commit the prior-term
 * entry and this test fail; phases 2 and the premises stay green.
 */
class Figure8CommitRestrictionTest {

    /**
     * One-way latency on every directed link. Two things ride on it: elections still converge
     * (`4 × linkLatency` of pre-vote + vote round trips sits well inside [raftCfg]'s election
     * window), and a freshly elected leader cannot receive an honest ack until `2 × linkLatency`
     * has passed — which is the window this test probes in.
     */
    private val linkLatency = 20.milliseconds

    /**
     * Virtual time allowed for one injected frame to reach the engine's actor loop and be applied.
     * Deliberately the same for both phases: the control (phase 2) proves this budget is sufficient,
     * which is what makes phase 1's silence evidence rather than vacuity.
     */
    private val probeStep = 2.milliseconds

    /**
     * Roomier than [fastRaftConfig]'s single-digit-millisecond timings so that [linkLatency] fits
     * inside an election window. Minted per test method (the framework builds the class per method),
     * seeded off [RAFT_TEST_SEED] so every election-timeout draw is reproducible.
     */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 150.milliseconds,
        electionTimeoutMax = 300.milliseconds,
        heartbeatInterval = 30.milliseconds,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    @Test
    fun priorTermEntryAtVoterQuorum_isWithheldUntilACurrentTermEntryCommits() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, config = raftCfg)
        val (v1, v2, v3) = sim.nodeIds
        sim.nodeIds.forEach { from ->
            sim.nodeIds.filter { it != from }.forEach { to -> sim.network.setLinkLatency(from, to, linkLatency) }
        }

        // A term-1 leader's AppendEntries, hand-delivered. leaderCommit = 0, so v1 and v2 hold the
        // entry UNCOMMITTED — the state a leader that crashed before its acks came back leaves behind.
        // v3's log stays empty, so §5.4.1 keeps it from winning what follows.
        listOf(v1, v2).forEach { to ->
            sim.deliverAppendEntries(
                to = to,
                from = v3,
                term = PRIOR_TERM,
                prevLogIndex = 0L,
                prevLogTerm = 0L,
                entries = listOf(LogEntry(index = 1L, term = PRIOR_TERM, command = byteArrayOf(7))),
                leaderCommit = 0L,
            )
        }

        val leader = sim.awaitLeader(among = setOf(v1, v2))
        val electedAt = testScheduler.currentTime
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val ackSource = setOf(v1, v2).first { it != leaderId }
        val leaderTerm = sim.storages.getValue(leaderId).term()
        val log = sim.storages.getValue(leaderId).entries(1L)
        val noOp = log.last()
        val priorTermTail = log.last { it.term < leaderTerm }
        val commitBefore = leader.commitIndex.value

        // Premises. Each is a property the probe below silently depends on; asserting them here is
        // what stops a future change to the setup from turning the pin vacuously green.
        assertAll(
            { assertTrue(leaderTerm > PRIOR_TERM, "leader must lead a term above the inherited entry's, was $leaderTerm") },
            { assertTrue(noOp.isNoOp && noOp.term == leaderTerm, "log tail must be this leader's own no-op, was $noOp") },
            { assertTrue(priorTermTail.index < noOp.index, "prior-term entry must sit below the no-op, was $priorTermTail") },
            { assertTrue(commitBefore < priorTermTail.index, "prior-term entry must still be uncommitted, commitIndex=$commitBefore") },
        )

        // Phase 1 — the pin. A voter quorum (leader + ackSource) now holds the PRIOR-TERM entry.
        sim.deliverAppendEntriesResponse(
            to = leaderId, from = ackSource, term = leaderTerm, success = true, matchIndex = priorTermTail.index,
        )
        delay(probeStep)
        val commitUnderPriorTermQuorum = leader.commitIndex.value

        // Phase 2 — the control. Same path, same budget, only the entry's term differs.
        sim.deliverAppendEntriesResponse(
            to = leaderId, from = ackSource, term = leaderTerm, success = true, matchIndex = noOp.index,
        )
        delay(probeStep)
        val commitUnderCurrentTermQuorum = leader.commitIndex.value
        val elapsed = testScheduler.currentTime - electedAt

        assertAll(
            {
                assertEquals(
                    commitBefore, commitUnderPriorTermQuorum,
                    "§5.4.2: index ${priorTermTail.index} (term ${priorTermTail.term}) is at a voter quorum but " +
                        "predates term $leaderTerm — replica count must NOT commit it",
                )
            },
            {
                assertTrue(
                    commitUnderCurrentTermQuorum >= noOp.index,
                    "control: index ${noOp.index} is this term's no-op at a voter quorum and must commit " +
                        "(carrying ${priorTermTail.index} with it), commitIndex=$commitUnderCurrentTermQuorum",
                )
            },
            {
                assertTrue(
                    elapsed < 2 * linkLatency.inWholeMilliseconds,
                    "both phases must land before the first honest ack could ($elapsed ms since election), " +
                        "or phase 2's commit is not attributable to the injected quorum",
                )
            },
        )
        sim.checkInvariants()
    }

    private companion object {
        /** Term of the inherited, uncommitted entry — strictly below whatever term the election produces. */
        const val PRIOR_TERM = 1L
    }
}
