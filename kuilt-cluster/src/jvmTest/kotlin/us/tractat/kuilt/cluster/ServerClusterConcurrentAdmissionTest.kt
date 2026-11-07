package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_BASE_CONFIG
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_SEED
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.raft.test.RAFT_SIM_WEDGE_BACKSTOP
import us.tractat.kuilt.raft.test.dumpOnWedge
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MemberIdentity
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.session.test.FakeRoom
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Concurrent learner admission through [ServerCluster] (#3120): admitting a second learner must never
 * evict the first.
 *
 * `admitLearner` used to build each admission's target from the cluster's *constructed* config,
 * `voters + {this learner}`, and `changeMembershipWithRetry` resent that stale target once the
 * previous change committed. So every admission replaced the learner set, and an earlier learner
 * stopped receiving replication while its own proposals still committed.
 *
 * ## Harness
 *
 * The voters **and** the learners are real [RaftNode]s on one [MultiNodeRaftSim] network, so
 * replication to a learner, and a learner's proposal forwarding, are the engine's own. Each learner's
 * connection is a [FakeRoom] whose roster names it, handed to the relay accept loop by
 * [ManyRoomsHost], which runs the admissions concurrently. The [RaftRelayHub] is real
 * but carries no Raft traffic here: the sim network does. What it contributes is the admission
 * bookkeeping under test.
 *
 * Every assertion is about the learners **receiving**: membership is necessary, and only a learner's
 * applied log shows that the leader is still replicating to it.
 */
class ServerClusterConcurrentAdmissionTest {

    @Test
    fun twoConcurrentAdmissionsKeepBothLearners() = admissionTest(learnerCount = 2) { h ->
        h.startRelay()
        h.awaitAllAdmitted()
        h.proposeFromEveryLearnerAndAwaitCrossVisibility()
        h.assertAllStillAdmitted()
    }

    @Test
    fun aStormOfTwentyAdmissionsKeepsEveryLearner() = admissionTest(learnerCount = 20) { h ->
        h.startRelay()
        h.awaitAllAdmitted()
        h.proposeFromEveryLearnerAndAwaitCrossVisibility()
        h.assertAllStillAdmitted()
    }

    /**
     * Leadership moves while the storm is being admitted, the shape of #3038: the admission must
     * re-read the new leader's membership rather than resend a target built against the old one, and
     * must not give a learner up because the leader it was talking to stepped down.
     */
    @Test
    fun leadershipTransferMidStormStillAdmitsEveryLearner() = admissionTest(
        learnerCount = 20,
        // Arrivals spread over two seconds, so the transfer below lands while most are still coming:
        // at one instant the reconciler would batch all twenty into one change before it started.
        arrivalGap = 100.milliseconds,
    ) { h ->
        val first = h.sim.awaitLeader(among = h.voters)
        h.startRelay()
        h.sim.awaitTrue("the storm has started committing", within = 5.seconds) {
            first.membership.value.learners.isNotEmpty()
        }
        val admittedAtTransfer = first.membership.value.learners.size
        val target = h.voters.first { h.sim.nodes.getValue(it) !== first }
        backgroundScope.launch {
            try {
                first.transferLeadership(target)
            } catch (_: Throwable) {
                // A transfer that cannot be confirmed in its window is not what is under test: the
                // assertion below is that admission converges under whichever leader emerges.
                currentCoroutineContext().ensureActive()
            }
        }
        h.sim.awaitTrue("leadership has left the first leader", within = 5.seconds) {
            h.sim.leader()?.let { it !== first } == true
        }
        // The rig fired: the transfer began with the storm unfinished, or this arm tests nothing.
        assertTrue(
            admittedAtTransfer < h.learners.size,
            "the transfer must start mid-storm; $admittedAtTransfer of ${h.learners.size} were already admitted",
        )
        h.awaitAllAdmitted()
        h.proposeFromEveryLearnerAndAwaitCrossVisibility()
        h.assertAllStillAdmitted()
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private class Harness(
        val scope: TestScope,
        val sim: MultiNodeRaftSim,
        val voters: Set<NodeId>,
        val learners: List<NodeId>,
        val cluster: ServerCluster,
    ) {
        private var relay: Job? = null

        fun startRelay() {
            relay = scope.backgroundScope.launch { cluster.start() }
        }

        /**
         * End every admission before the test scope tears the rest down. Cancelling both at once
         * lets an admission's `finally` evict from an overlay whose directory has already closed
         * itself, which throws. That teardown ordering predates the reconciler and is not what is
         * under test here.
         */
        suspend fun stopRelay() {
            relay?.cancelAndJoin()
        }

        suspend fun awaitAllAdmitted() {
            sim.awaitTrue("every learner is in the leader's membership", within = ADMISSION_BUDGET) {
                sim.leader()?.membership?.value?.learners == learners.toSet()
            }
        }

        /**
         * Each learner proposes one byte of its own (forwarded to the leader), then every learner must
         * apply every learner's byte. A learner the leader stopped replicating to never sees the bytes
         * proposed after it was dropped.
         */
        suspend fun proposeFromEveryLearnerAndAwaitCrossVisibility() {
            learners.forEachIndexed { i, id ->
                scope.backgroundScope.launch {
                    try {
                        sim.nodes.getValue(id).propose(byteArrayOf(i.toByte()))
                    } catch (_: Throwable) {
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            val every = learners.indices.map { it.toByte() }.toSet()
            sim.awaitTrue("every learner has applied every learner's proposal", within = ADMISSION_BUDGET) {
                learners.all { sim.appliedState(it).toSet().containsAll(every) }
            }
        }

        /** Admission is settled, not merely reached once: an evicting retry would land after it. */
        suspend fun assertAllStillAdmitted() {
            delay(SETTLE)
            val leader = sim.awaitLeader(among = voters)
            val leaderId = voters.first { sim.nodes[it] === leader }
            val leaderState = sim.appliedState(leaderId)
            assertAll(
                { assertEquals(learners.toSet(), leader.membership.value.learners, "no learner was evicted") },
                { assertEquals(voters, leader.membership.value.voters, "admission never touches the voter set") },
                {
                    val behind = learners.filterNot { sim.appliedState(it).contentEquals(leaderState) }
                    assertTrue(behind.isEmpty(), "every learner has applied exactly the leader's log; behind: $behind")
                },
            )
        }
    }

    private fun admissionTest(
        learnerCount: Int,
        arrivalGap: Duration = Duration.ZERO,
        body: suspend TestScope.(Harness) -> Unit,
    ) =
        runTest(StandardTestDispatcher(), timeout = RAFT_SIM_WEDGE_BACKSTOP) {
            val voters = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
            val learners = (1..learnerCount).map { NodeId("l$it") }
            val all = voters + learners
            val voterConfig = ClusterConfig(voters = voters.toSet())
            val sim = MultiNodeRaftSim(
                nodeIds = all,
                scope = this,
                nodeScope = backgroundScope,
                nodeFactory = { id, transport, storage, childScope ->
                    val config = if (id in voters) voterConfig else voterConfig.copy(learners = setOf(id))
                    val seed = MULTI_NODE_SIM_SEED + all.indexOf(id)
                    childScope.raftNode(
                        config,
                        transport,
                        storage,
                        MULTI_NODE_SIM_BASE_CONFIG.copy(random = Random(seed)),
                    )
                },
            )
            val cluster = backgroundScope.clusterOver(sim, voters, learners, arrivalGap)
            val harness = Harness(this, sim, voters.toSet(), learners, cluster)
            dumpOnWedge(sim) { body(harness) }
            harness.stopRelay()
        }

    private fun CoroutineScope.clusterOver(
        sim: MultiNodeRaftSim,
        voters: List<NodeId>,
        learners: List<NodeId>,
        arrivalGap: Duration,
    ): ServerCluster {
        val self = PeerId(voters.first().value)
        val rooms = learners.map { id ->
            FakeRoom(
                selfId = self,
                initialRoster = setOf(
                    Member(PeerId(id.value), MemberIdentity(displayName = id.value, sessionId = id.value), Liveness.Connected),
                ),
            )
        }
        return ServerCluster(
            mesh = VoterMesh(voterNodes = voters.associateWith { sim.nodes.getValue(it) }, scope = this),
            host = ManyRoomsHost(rooms, arrivalGap),
            hub = RaftRelayHub(voters.toSet()),
            overlay = overlayServer(
                self = self,
                coreSeam = FakeSeam(selfId = self),
                directorySeam = FakeSeam(selfId = self),
                scope = this,
                clock = { 0L },
                directoryConfig = QuilterConfig(expectVirtualTime = true),
            ),
            serverScope = this,
        )
    }

    private companion object {
        /** Virtual time for an admission storm to converge: generous against 200 ms retry backoffs. */
        val ADMISSION_BUDGET = 30.seconds

        /** A quiet stretch after convergence, long enough for any stale retry to have landed. */
        val SETTLE = 5.seconds
    }
}

/**
 * Hands each room to the relay in its own coroutine, as a real accept loop would: all at one instant,
 * or one every [arrivalGap].
 */
private class ManyRoomsHost(private val rooms: List<Room>, private val arrivalGap: Duration) : RoomHost {
    override suspend fun start(onRoom: suspend (Room) -> Unit): Unit = coroutineScope {
        rooms.forEach { room ->
            launch { onRoom(room) }
            if (arrivalGap > Duration.ZERO) delay(arrivalGap)
        }
    }

    override fun close(): Unit = Unit
}
