@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1718: [RaftNode.readIndex] returns Raft's **commit index**, which counts entries the
 * application-facing stream deliberately withholds — the §5.4.2 election no-op and the §6 config
 * entries. A state machine that folds [RaftNode.committedFrom] must still be able to *reach*
 * `readIndex()`, or the natural freshness check
 *
 * ```kotlin
 * val fenced = readIndex()
 * if (myAppliedIndex < fenced) refuse()   // or wait
 * ```
 *
 * refuses (or blocks) forever right after an election or a membership change, until unrelated
 * application traffic happens to commit past the gap.
 *
 * Each test drives the canonical [RaftSimulation] harness, folds the stream exactly as a consumer
 * would ([appliedIndex]), and asserts the applied prefix reaches the fenced read index.
 */
class AppliedPrefixReachesReadIndexTest {

    private val solo = NodeId("solo")

    private fun soloSim(scope: CoroutineScope): RaftSimulation {
        val config = ClusterConfig(voters = setOf(solo))
        return RaftSimulation(
            nodeIds = listOf(solo),
            scope = scope,
            raftConfig = FAST_RAFT_CONFIG,
        ) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
        }
    }

    /**
     * The committed index a folding state machine advances its applied prefix to on this
     * instruction — one branch per [Committed] variant, exactly as a consumer's apply loop writes it.
     */
    private fun Committed.appliedIndex(): Long = when (this) {
        is Committed.Entry -> entry.index
        is Committed.Internal -> index
        is Committed.Install -> snapshot.throughIndex
    }

    /** Fold [node]'s stream from [from] into an applied-prefix flow seeded at `from - 1`. */
    private fun CoroutineScope.foldApplied(node: RaftNode, from: Long): MutableStateFlow<Long> {
        val applied = MutableStateFlow(from - 1L)
        launch { node.committedFrom(from).collect { applied.value = maxOf(applied.value, it.appliedIndex()) } }
        return applied
    }

    /**
     * The §5.4.2 no-op arm of `advanceCommit`, taken **live**: the consumer subscribes before the
     * election, so the no-op reaches it (or not) through the live commit path rather than replay.
     * With nothing else proposed, the no-op is the only committed index — the applied prefix reaches
     * `readIndex()` only if the withheld entry is accounted for.
     */
    @Test
    fun appliedPrefixReachesReadIndexAcrossTheElectionNoOp_liveArm() = raftRunTest {
        val sim = soloSim(backgroundScope)
        val node = sim.nodes.getValue(solo)
        val applied = backgroundScope.foldApplied(node, from = 1L)
        // Subscribe at virtual t=0, before the election timer fires, so the no-op commits live.
        sim.settle()

        val leader = awaitLeader(sim)
        val fenced = leader.readIndex()

        assertTrue(fenced >= 1L, "a freshly elected leader's readIndex must count the no-op, was $fenced")
        sim.awaitTrue("applied prefix reaches readIndex()=$fenced (live no-op arm), stuck at ${applied.value}") {
            applied.value >= fenced
        }
    }

    /**
     * The same gap on the **replay** path: a consumer that subscribes after the election must still
     * be able to account for the no-op already in the committed log.
     */
    @Test
    fun appliedPrefixReachesReadIndexAcrossTheElectionNoOp_replayArm() = raftRunTest {
        val sim = soloSim(backgroundScope)
        val leader = awaitLeader(sim)
        sim.awaitCommit(1L)

        val applied = backgroundScope.foldApplied(leader, from = 1L)
        sim.settle()

        val fenced = leader.readIndex()
        sim.awaitTrue("applied prefix reaches readIndex()=$fenced (replay no-op arm), stuck at ${applied.value}") {
            applied.value >= fenced
        }
    }

    /**
     * The §6 config arm of `advanceCommit`, taken **live**. The fold starts *above* the election
     * no-op, so the config entry is the only withheld index in the window — this test reddens for
     * the config arm alone.
     */
    @Test
    fun appliedPrefixReachesReadIndexAcrossAConfigEntry_liveArm() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val entry = sim.proposeOnLeader(byteArrayOf(1))

        // Resume as a consumer would: applied through `entry.index`, tailing from the next index.
        val applied = backgroundScope.foldApplied(leader, from = entry.index + 1)
        sim.settle()

        // A learner-set-only change appends exactly one Simple config entry (no joint transition).
        sim.changeMembershipOnLeader(ClusterConfig(voters = sim.nodeIds.toSet(), learners = setOf(NodeId("L1"))))

        val fenced = leader.readIndex()
        assertTrue(fenced > entry.index, "the config entry must have advanced the commit index past ${entry.index}")
        sim.awaitTrue("applied prefix reaches readIndex()=$fenced (live config arm), stuck at ${applied.value}") {
            applied.value >= fenced
        }
    }

    /**
     * The withheld entries are marked, not delivered: the marker carries the committed index and
     * nothing else, in index order, interleaved correctly with the application entries around it.
     */
    @Test
    fun withheldEntriesSurfaceAsIndexOnlyMarkersInOrder() = raftRunTest {
        val sim = soloSim(backgroundScope)
        val node = sim.nodes.getValue(solo)
        val seen = mutableListOf<Committed>()
        backgroundScope.launch { node.committedFrom(1L).collect { seen += it } }
        sim.settle()

        val leader = awaitLeader(sim)
        val e1 = leader.propose(byteArrayOf(7))
        sim.awaitCommit(e1.index)
        sim.settle()

        fun kindOf(c: Committed): String = when (c) {
            is Committed.Entry -> "Entry"
            is Committed.Internal -> "Internal"
            is Committed.Install -> "Install"
        }

        assertEquals(
            listOf("Internal" to 1L, "Entry" to e1.index),
            seen.map { c -> kindOf(c) to c.appliedIndex() },
            "the election no-op must surface as an index-only marker at its own committed index, " +
                "ordered before the application entry that follows it: $seen",
        )
    }
}
