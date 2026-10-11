@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2674's accepted limitation: an armed learner keeps the first voter's claim, even if the
 * elected leader arrives second. These are ordered frame injections, not a scheduling race.
 *
 * The victim uses a fresh [DurableStoreRaftStorage] handle after restart, exercising encoding and
 * restore. Its byte store survives the simulated crash; this does not test physical crash safety.
 * The simulation's default storage for the victim is unused, so assertions read [Fixture.storage].
 */
class ArmedLeaderPinTest {
    private val voters = (1..3).map { NodeId("v$it") }
    private val victim = NodeId("learner")
    private val stranger = NodeId("never-a-voter")

    private class Fixture(
        val sim: RaftSimulation,
        val store: InMemoryDurableStore,
        val handles: MutableMap<NodeId, DurableStoreRaftStorage>,
        val victim: NodeId,
        val leader: NodeId,
        val forger: NodeId,
        val term: Long,
        val entries: List<LogEntry>,
    ) {
        val storage: DurableStoreRaftStorage get() = handles.getValue(victim)
        val node: RaftNode get() = sim.nodes.getValue(victim)

        suspend fun claim(from: NodeId, claimTerm: Long = term, withLog: Boolean = false) {
            sim.deliverAppendEntries(
                to = victim, from = from, term = claimTerm,
                entries = if (withLog) entries else emptyList(),
                leaderCommit = if (withLog) entries.last().index else 0L,
            )
            sim.settle()
        }
    }

    private suspend fun TestScope.fixture(armed: Boolean = true): Fixture {
        val store = InMemoryDurableStore()
        val handles = mutableMapOf(victim to DurableStoreRaftStorage.open(store))
        val cluster = ClusterConfig(voters = voters.toSet())
        val seed = ClusterConfig(
            voters = if (armed) voters.toSet() else emptySet(), learners = setOf(victim),
        )
        val config = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = voters + victim, scope = this, nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, childScope ->
                childScope.raftNode(
                    if (id == victim) seed else cluster, transport,
                    if (id == victim) handles.getValue(id) else storage, config,
                )
            },
        )
        sim.partitionOff(victim)
        val leaderNode = sim.awaitLeader()
        val leader = voters.first { sim.nodes[it] === leaderNode }
        val committed = sim.proposeOnLeader(byteArrayOf(0x42))
        sim.awaitCommit(committed.index, on = voters.toSet())
        val fixture = Fixture(
            sim, store, handles, victim, leader, voters.first { it != leader },
            sim.storages.getValue(leader).term(), sim.storages.getValue(leader).entries(),
        )
        val initialPin = fixture.storage.leaderForTerm()
        val initialLog = fixture.storage.entries()
        assertAll(
            { assertEquals(seed, fixture.node.membership.value, "premise: the chosen authority window") },
            { assertIs<RaftRole.Leader>(leaderNode.role.value, "premise: the elected claimant still leads") },
            { assertIs<RaftRole.Follower>(sim.nodes.getValue(fixture.forger).role.value) },
            { assertIs<RaftRole.Learner>(fixture.node.role.value) },
            { assertNull(initialPin, "premise: neither claimant has arrived") },
            { assertTrue(initialLog.isEmpty(), "premise: no conflicting log") },
        )
        return fixture
    }

    private suspend fun TestScope.refusals(f: Fixture): MutableList<RaftTraceEvent.FrameRefused> {
        val events = mutableListOf<RaftTraceEvent.FrameRefused>()
        backgroundScope.launch {
            f.node.trace.collect { if (it is RaftTraceEvent.FrameRefused) events += it }
        }
        f.sim.settle()
        return events
    }

    private fun assertRefused(events: List<RaftTraceEvent.FrameRefused>, from: NodeId) {
        assertEquals(1, events.size, "exactly the injected conflict must be refused: $events")
        assertAll(
            { assertEquals(RefusalGate.ForgedLeaderForTerm, events.single().gate) },
            { assertEquals(from, events.single().from) },
        )
    }

    @Test
    fun aVoterArrivingFirstDeniesTheElectedLeaderAtTheSameTerm() = raftRunTest {
        val f = fixture()
        f.claim(f.forger)
        val events = refusals(f)
        f.claim(f.leader, withLog = true)

        assertRefused(events, f.leader)
        val pin = f.storage.leaderForTerm()
        val term = f.storage.term()
        val log = f.storage.entries()
        assertAll(
            { assertEquals(f.forger, f.node.leader.value, "arrival order wins over election elsewhere") },
            { assertEquals(LeaderForTerm(f.term, f.forger), pin) },
            { assertEquals(f.term, term, "no term inflation") },
            { assertTrue(log.isEmpty(), "the honest log was denied") },
            { assertEquals(0L, f.node.commitIndex.value, "the honest commit was denied") },
        )
    }

    @Test
    fun theElectedLeaderArrivingFirstRefusesTheLaterVoterClaim() = raftRunTest {
        val f = fixture()
        f.claim(f.leader, withLog = true)
        val events = refusals(f)
        f.claim(f.forger)

        assertRefused(events, f.forger)
        val pin = f.storage.leaderForTerm()
        val log = f.storage.entries()
        assertAll(
            { assertEquals(f.leader, f.node.leader.value) },
            { assertEquals(LeaderForTerm(f.term, f.leader), pin) },
            { assertEquals(f.entries, log, "the honest log was admitted") },
            { assertEquals(f.entries.last().index, f.node.commitIndex.value) },
        )
    }

    @Test
    fun reopeningDurableStorageRetainsTheRefusalEvenWithNoLiveLeader() = raftRunTest {
        val f = fixture()
        f.claim(f.forger)
        f.sim.crash(victim)
        f.handles[victim] = DurableStoreRaftStorage.open(f.store)
        f.sim.restart(victim)
        f.sim.settle()
        val restoredPin = f.storage.leaderForTerm()
        val restoredTerm = f.storage.term()
        assertAll(
            { assertEquals(f.term, restoredTerm, "restart stays inside the claimed term") },
            { assertEquals(LeaderForTerm(f.term, f.forger), restoredPin) },
            { assertNull(f.node.leader.value, "the live pointer is not the durable pin") },
        )
        val events = refusals(f)
        f.claim(f.leader, withLog = true)

        assertRefused(events, f.leader)
        val pin = f.storage.leaderForTerm()
        val log = f.storage.entries()
        assertAll(
            { assertNull(f.node.leader.value, "refusal survives without a live leader pointer") },
            { assertTrue(log.isEmpty(), "restart does not restore progress") },
            { assertEquals(0L, f.node.commitIndex.value) },
            { assertEquals(LeaderForTerm(f.term, f.forger), pin) },
        )
    }

    @Test
    fun aHigherTermReopensAdoptionAndLogProgress() = raftRunTest {
        val f = fixture()
        f.claim(f.forger)
        val events = refusals(f)
        f.claim(f.leader, withLog = true)
        assertRefused(events, f.leader)
        events.clear()

        // Inject a later-term leader contact; this tests adoption, not how an election is triggered.
        f.claim(f.leader, claimTerm = f.term + 1, withLog = true)
        val pin = f.storage.leaderForTerm()
        val term = f.storage.term()
        val log = f.storage.entries()
        assertAll(
            { assertTrue(events.isEmpty(), "the old pin must not refuse the new term") },
            { assertEquals(f.term + 1, term) },
            { assertEquals(f.leader, f.node.leader.value) },
            { assertEquals(LeaderForTerm(f.term + 1, f.leader), pin) },
            { assertEquals(f.entries, log) },
            { assertEquals(f.entries.last().index, f.node.commitIndex.value, "log progress resumes") },
        )
    }

    @Test
    fun anUnarmedLearnerAdmitsBothClaimsWithoutCreatingAPin() = raftRunTest {
        val f = fixture(armed = false)
        val events = refusals(f)
        f.claim(stranger)
        val firstPin = f.storage.leaderForTerm()
        assertAll(
            { assertEquals(stranger, f.node.leader.value, "the first claim reached the handler") },
            { assertNull(firstPin, "unarmed contact must not create a durable pin") },
        )
        f.claim(f.leader, withLog = true)
        val pin = f.storage.leaderForTerm()
        val log = f.storage.entries()
        assertAll(
            { assertTrue(events.isEmpty(), "the unarmed exception admits the later honest claim") },
            { assertEquals(f.leader, f.node.leader.value) },
            { assertNull(pin) },
            { assertEquals(f.entries, log) },
            { assertEquals(f.entries.last().index, f.node.commitIndex.value) },
        )
    }
}
