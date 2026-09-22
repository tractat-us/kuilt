package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire gate and the restore bound must agree on which [ConfigPayload]s name no active voters
 * (#2840).
 *
 * A config reaches this node two ways. Over the wire, `configPayloadRefusal` drops it. From storage,
 * `checkedRestoredEntries` refuses to start on it, and so does `checkedRestoredSnapshotMeta` when the
 * bootstrap is a learner seed (#2838). What passes the first is written to storage and read back by
 * the second on the next start. So if the wire gate ever admits a config the restore refuses, a seed
 * can take a snapshot its own build accepted, and then fail to start on the next restart.
 *
 * This runs one table through all four doors and asserts they agree, row by row:
 *
 * - **wire, `AppendEntries`** — the config rides an entry the pinned leader sends;
 * - **wire, `InstallSnapshot`** — the config rides a snapshot the pinned leader sends;
 * - **restore, log entry** — the config sits on a stored entry of a seated node;
 * - **restore, snapshot on the learner seed** — the config sits on a stored snapshot of a seed, the
 *   one bootstrap where the restore refuses rather than falls back.
 *
 * Each row also names the verdict it expects. Agreement alone would pass if every door broke the same
 * way; the expected column stops that, and the two polarities in the table keep each other honest. A
 * rig that never reached its gate reads `Admitted` and reds every refused row. A rig that refused for
 * some other reason reads [Verdict.Other] and reds everything.
 *
 * A wire verdict is `Refused` only when the refusal names [RefusalGate.ConfigPayloadEmptyVoterSet],
 * and `Admitted` only when the config reached durable storage. A restore verdict is `Refused` only
 * when the [CorruptDurableStateException] names the voterless config and the field it came from.
 */
internal class NoActiveVotersAgreementTest {

    private val a = NodeId("c1")
    private val b = NodeId("c2")
    private val c = NodeId("c3")
    private val learner = NodeId("l1")

    private val seated = ClusterConfig(voters = setOf(a, b))
    private val seatedWithLearner = ClusterConfig(voters = setOf(a, b), learners = setOf(learner))
    private val empty = ClusterConfig(voters = emptySet())
    private val learnersOnly = ClusterConfig(voters = emptySet(), learners = setOf(learner))

    private class Row(val name: String, val config: ConfigPayload, val expected: Verdict)

    private sealed interface Verdict {
        data object Refused : Verdict
        data object Admitted : Verdict
        data class Other(val detail: String) : Verdict
    }

    private val table = listOf(
        Row("simple, no voters", ConfigPayload(old = null, new = empty), Verdict.Refused),
        Row("simple, learners but no voters", ConfigPayload(old = null, new = learnersOnly), Verdict.Refused),
        Row("joint, old has no voters", ConfigPayload(old = empty, new = seated), Verdict.Refused),
        Row("joint, new has no voters", ConfigPayload(old = seated, new = empty), Verdict.Refused),
        Row("joint, neither side has voters", ConfigPayload(old = empty, new = empty), Verdict.Refused),
        Row("joint, old has learners but no voters", ConfigPayload(old = learnersOnly, new = seated), Verdict.Refused),
        Row("joint, new has learners but no voters", ConfigPayload(old = seated, new = learnersOnly), Verdict.Refused),
        Row("joint, both sides learners only", ConfigPayload(old = learnersOnly, new = learnersOnly), Verdict.Refused),
        Row("simple, seated", ConfigPayload(old = null, new = seated), Verdict.Admitted),
        Row("simple, seated with a learner", ConfigPayload(old = null, new = seatedWithLearner), Verdict.Admitted),
        Row(
            "joint, both sides seated",
            ConfigPayload(old = seated, new = ClusterConfig(voters = setOf(b, c))),
            Verdict.Admitted,
        ),
        Row(
            "joint, both sides seated with a learner",
            ConfigPayload(old = seatedWithLearner, new = ClusterConfig(voters = setOf(c), learners = setOf(learner))),
            Verdict.Admitted,
        ),
    )

    @Test
    fun theWireGateAndTheRestoreBoundAgreeOnEveryRow() = raftRunTest {
        val results = table.map { row ->
            val (wireAppend, wireSnapshot) = wireVerdicts(row.config)
            row to mapOf(
                "wire/AppendEntries" to wireAppend,
                "wire/InstallSnapshot" to wireSnapshot,
                "restore/log entry" to restoreEntryVerdict(row.config),
                "restore/snapshot on seed" to restoreSeedSnapshotVerdict(row.config),
            )
        }

        assertAll(
            { assertEquals(12, results.size, "precondition: every row of the table ran") },
            *results.flatMap { (row, verdicts) ->
                listOf(
                    {
                        assertEquals(
                            1, verdicts.values.toSet().size,
                            "[${row.name}] the four doors must agree, but gave $verdicts",
                        )
                    },
                    {
                        assertEquals(
                            verdicts.keys.associateWith { row.expected }, verdicts,
                            "[${row.name}] expected every door to say ${row.expected}",
                        )
                    },
                )
            }.toTypedArray(),
        )
    }

    // ── The wire gate ─────────────────────────────────────────────────────────

    /**
     * Deliver [config] from the pinned leader to two followers — on an `AppendEntries` entry to one,
     * on an `InstallSnapshot` to the other — and report each verdict.
     *
     * Every other bound upstream is satisfied rather than unreached: the sender is the leader both
     * followers pinned, at their own term, and the batch is contiguous from each follower's own tail.
     * Both followers are partitioned off first, so their replies cannot move the leader, and the rig
     * only [RaftSimulation.settle]s, which advances no virtual time and fires no timer. Each row gets
     * a fresh cluster in its own scope, cancelled at the end, so no row sees another's config.
     */
    private suspend fun TestScope.wireVerdicts(config: ConfigPayload): Pair<Verdict, Verdict> {
        val rowScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        try {
            val sim = raftSim(this, rowScope)
            val leader = sim.awaitLeader()
            val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
            val (toAppend, toSnapshot) = sim.nodeIds.filter { it != leaderId }
            sim.awaitTrue("both followers pinned $leaderId and committed its no-op") {
                listOf(toAppend, toSnapshot).all {
                    val node = sim.nodes.getValue(it)
                    node.leader.value == leaderId && node.commitIndex.value >= 1L
                }
            }
            val term = sim.storages.getValue(toAppend).term()
            sim.partitionOff(toAppend)
            sim.partitionOff(toSnapshot)

            val refusals = mutableListOf<RaftTraceEvent.FrameRefused>()
            sim.nodes.values.forEach { node ->
                rowScope.launch { node.trace.collect { if (it is RaftTraceEvent.FrameRefused) refusals += it } }
            }
            sim.settle()
            refusals.clear()

            val appendStorage = sim.storages.getValue(toAppend)
            val tail = appendStorage.entries().last()
            val appended = LogEntry(index = tail.index + 1L, term = term, command = byteArrayOf(), config = config)
            sim.deliverAppendEntries(
                to = toAppend, from = leaderId, term = term,
                prevLogIndex = tail.index, prevLogTerm = tail.term,
                entries = listOf(appended),
            )
            sim.settle()
            val appendVerdict = wireVerdict(
                refusals.filter { it.node == toAppend },
                landed = appendStorage.entries().any { it.index == appended.index && it.config == config },
            )

            val snapshotStorage = sim.storages.getValue(toSnapshot)
            sim.deliverInstallSnapshot(
                to = toSnapshot, from = leaderId, term = term,
                lastIncludedIndex = sim.nodes.getValue(toSnapshot).commitIndex.value + 5L, lastIncludedTerm = term,
                data = byteArrayOf(1, 2, 3),
                config = config,
            )
            sim.settle()
            val snapshotVerdict = wireVerdict(
                refusals.filter { it.node == toSnapshot },
                landed = snapshotStorage.loadSnapshot()?.meta?.config == config,
            )
            return appendVerdict to snapshotVerdict
        } finally {
            rowScope.cancel()
        }
    }

    private fun wireVerdict(refusals: List<RaftTraceEvent.FrameRefused>, landed: Boolean): Verdict = when {
        refusals.size == 1 && refusals.single().gate == RefusalGate.ConfigPayloadEmptyVoterSet && !landed ->
            Verdict.Refused
        refusals.isEmpty() && landed -> Verdict.Admitted
        else -> Verdict.Other("refusals=${refusals.map { it.gate }}, landed=$landed")
    }

    // ── The restore bound ─────────────────────────────────────────────────────

    /** Start a seated single voter over a log whose one entry carries [config]. */
    private suspend fun TestScope.restoreEntryVerdict(config: ConfigPayload): Verdict {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(RESTORED_TERM, null)
        storage.appendEntries(
            listOf(LogEntry(index = 1L, term = RESTORED_TERM, command = byteArrayOf(), config = config)),
        )
        return restoreVerdict(awaitRestoreFailure(storage), provenance = "index=1")
    }

    /** Start the learner seed over a snapshot carrying [config] — where the restore refuses (#2838). */
    private suspend fun TestScope.restoreSeedSnapshotVerdict(config: ConfigPayload): Verdict {
        val self = NodeId("seed")
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(RESTORED_TERM, null)
        storage.saveSnapshot(SnapshotMeta(SNAPSHOT_INDEX, 1L, config), byteArrayOf(1))
        val seed = ClusterConfig(voters = emptySet(), learners = setOf(self))
        val failure = awaitRestoreFailure(storage) { scope ->
            scope.raftNode(seed, InMemoryRaftNetwork().transport(self), storage, fastRaftConfig())
        }
        return restoreVerdict(failure, provenance = "lastIncludedIndex=$SNAPSHOT_INDEX")
    }

    private fun restoreVerdict(failure: Throwable?, provenance: String): Verdict {
        val message = failure?.message.orEmpty()
        return when {
            failure == null -> Verdict.Admitted
            failure is CorruptDurableStateException && "names no voters" in message && provenance in message ->
                Verdict.Refused
            else -> Verdict.Other("$failure")
        }
    }

    private companion object {
        const val RESTORED_TERM = 5L

        /** Well inside `MAX_PLAUSIBLE_INDEX`, so the snapshot's index bound is not what fires. */
        const val SNAPSHOT_INDEX = 1L shl 40
    }
}
