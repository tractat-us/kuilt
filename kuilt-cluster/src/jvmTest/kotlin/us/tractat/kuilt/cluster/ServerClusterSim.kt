package us.tractat.kuilt.cluster

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MemberIdentity
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.session.test.FakeRoom
import us.tractat.kuilt.test.FakeSeam
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [ServerCluster] whose voters **and** learners are real [RaftNode]s on one [MultiNodeRaftSim]
 * network, so replication to a learner and a learner's proposal forwarding are the engine's own.
 *
 * Each learner's connection is a [FakeRoom] whose roster names it, handed to the relay accept loop by
 * [ControlledRoomHost]: the initial learners on [startRelay], then any [connect] or [disconnect] a
 * test asks for. The [RaftRelayHub] is real but carries no Raft traffic: the sim network does. What it
 * contributes is the admission bookkeeping under test.
 */
internal class ServerClusterSim(
    val scope: TestScope,
    val sim: MultiNodeRaftSim,
    val voters: Set<NodeId>,
    val learners: List<NodeId>,
    val cluster: ServerCluster,
    private val host: ControlledRoomHost,
) {
    private var relay: Job? = null

    fun startRelay() {
        relay = scope.backgroundScope.launch { cluster.start() }
    }

    /** A new connection for [id], as after a tear or on another relay. */
    suspend fun connect(id: NodeId) = host.connect(id)

    /**
     * Close [id]'s [which] connection as a transport tear does. Nothing cancels its admission: it must
     * notice the close itself, as it must in production.
     */
    suspend fun disconnect(id: NodeId, which: ControlledRoomHost.Which = ControlledRoomHost.Which.Newest) =
        host.disconnect(id, which)

    /** [id] leaves its newest connection's roster (a goodbye, or an expired partition); the room stays up. */
    suspend fun depart(id: NodeId) = host.depart(id)

    /** Learners whose closed connection is still admitted. */
    fun admissionsStillRunningAfterClose(): List<NodeId> = host.admissionsStillRunningAfterClose()

    /** [id]'s newest open connection's room. */
    fun newestRoom(id: NodeId): FakeRoom = checkNotNull(host.newestRoom(id)) { "$id has no open connection" }

    /**
     * End every admission before the test scope tears the rest down. Cancelling both at once lets an
     * admission's `finally` evict from an overlay whose directory has already closed itself, which
     * throws. That teardown ordering predates the reconciler and is not what is under test here.
     */
    suspend fun stopRelay() {
        relay?.cancelAndJoin()
    }

    fun leaderLearners(): Set<NodeId>? = sim.leader()?.membership?.value?.learners

    suspend fun awaitLeaderLearners(expected: Set<NodeId>, what: String, within: Duration = ADMISSION_BUDGET) {
        sim.awaitTrue(what, within) { leaderLearners() == expected }
    }

    /**
     * Each of [among] proposes one byte of its own (forwarded to the leader), then each must apply
     * every one of those bytes. A learner the leader stopped replicating to never sees the bytes
     * proposed after it was dropped. [tag] keeps one round's bytes distinct from another's.
     */
    suspend fun proposeAndAwaitCrossVisibility(among: List<NodeId> = learners, tag: Int = 0) {
        val bytes = among.associateWith { (tag * 64 + learners.indexOf(it)).toByte() }
        bytes.forEach { (id, byte) ->
            scope.backgroundScope.launch {
                try {
                    sim.nodes.getValue(id).propose(byteArrayOf(byte))
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        val every = bytes.values.toSet()
        sim.awaitTrue("each of $among has applied every proposal of round $tag", within = ADMISSION_BUDGET) {
            among.all { sim.appliedState(it).toSet().containsAll(every) }
        }
    }

    /** The ids in [among] whose applied log is not exactly the leader's. */
    suspend fun behindTheLeader(among: List<NodeId>): List<NodeId> {
        val leader = sim.awaitLeader(among = voters)
        val leaderState = sim.appliedState(voters.first { sim.nodes[it] === leader })
        return among.filterNot { sim.appliedState(it).contentEquals(leaderState) }
    }

    companion object {
        /**
         * Virtual time for admission to converge: generous against 200 ms retry backoffs and a
         * two-second arrival spread, and short enough that a non-converging run fails here, with the
         * sim's state dump, well before the wall-clock wedge backstop.
         */
        val ADMISSION_BUDGET = 10.seconds
    }
}

/**
 * Run [body] against a [ServerClusterSim] with three voters and [learnerCount] learners, all of whom
 * connect when the relay starts, one every [arrivalGap].
 */
internal fun serverClusterSimTest(
    learnerCount: Int,
    learnerRemoval: LearnerRemoval,
    arrivalGap: Duration = Duration.ZERO,
    body: suspend TestScope.(ServerClusterSim) -> Unit,
): TestResult = runTest(StandardTestDispatcher(), timeout = RAFT_SIM_WEDGE_BACKSTOP) {
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
            childScope.raftNode(
                config,
                transport,
                storage,
                MULTI_NODE_SIM_BASE_CONFIG.copy(random = Random(MULTI_NODE_SIM_SEED + all.indexOf(id))),
            )
        },
    )
    val self = PeerId(voters.first().value)
    val host = ControlledRoomHost(self, learners, arrivalGap)
    val cluster = ServerCluster(
        mesh = VoterMesh(voterNodes = voters.associateWith { sim.nodes.getValue(it) }, scope = backgroundScope),
        host = host,
        hub = RaftRelayHub(voters.toSet()),
        overlay = overlayServer(
            self = self,
            coreSeam = FakeSeam(selfId = self),
            directorySeam = FakeSeam(selfId = self),
            scope = backgroundScope,
            clock = { 0L },
            directoryConfig = QuilterConfig(expectVirtualTime = true),
        ),
        serverScope = backgroundScope,
        learnerRemoval = learnerRemoval,
    )
    val harness = ServerClusterSim(this, sim, voters.toSet(), learners, cluster, host)
    dumpOnWedge(sim) { body(harness) }
    harness.stopRelay()
}


/**
 * Hands each learner's room to the relay in its own coroutine, as a real accept loop would: the
 * [initial] learners as soon as the relay starts (one every [arrivalGap]), then one per [connect].
 *
 * A connection ends the way production ends it, never by cancelling its `onRoom` coroutine, which no
 * production host does (#3127). [disconnect] closes it as the host-side `SeamRoom` does on a transport
 * tear: the seam reads Torn, then the room leaves, which empties its roster. [depart] is the softer
 * shape: the member leaves the roster (its goodbye, or a partition that expired) while the room stays
 * up. When `onRoom` returns the host leaves the room, as `KtorRoomHost` does.
 *
 * Every connection keeps its own handle, so a test can close a superseded one while a newer one for the
 * same learner is live. Every command is applied by the accept loop itself, so the handle map has one
 * owner.
 */
internal class ControlledRoomHost(
    private val self: PeerId,
    private val initial: List<NodeId>,
    private val arrivalGap: Duration,
) : RoomHost {
    private sealed interface Command {
        data class Connect(val id: NodeId) : Command
        data class Close(val id: NodeId, val which: Which, val how: How) : Command
    }

    /** Which of a learner's connections a [Command.Close] ends. */
    enum class Which { Newest, Oldest }

    private enum class How { Tear, Depart }

    /** One accepted connection: its room and the `onRoom` coroutine admitting it. */
    private class Connection(val room: FakeRoom, val admission: Job)

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /**
     * Every connection a close command has ended, in close order. Appended only by the accept loop and
     * read by the test, both on the one test dispatcher thread.
     */
    private val closed = mutableListOf<Pair<NodeId, Connection>>()

    suspend fun connect(id: NodeId) = commands.send(Command.Connect(id))

    /** Close [id]'s [which] connection as a transport tear does. */
    suspend fun disconnect(id: NodeId, which: Which = Which.Newest) = commands.send(Command.Close(id, which, How.Tear))

    /** [id] leaves its [which] connection's roster while that room stays up. */
    suspend fun depart(id: NodeId, which: Which = Which.Newest) = commands.send(Command.Close(id, which, How.Depart))

    /**
     * The learners whose closed connection's admission is still running. Nothing cancels it, as in
     * production, so a closed connection whose admission never notices the close stays listed here.
     */
    fun admissionsStillRunningAfterClose(): List<NodeId> =
        closed.filterNot { (_, connection) -> connection.admission.isCompleted }.map { it.first }

    /** Each learner's open connections, oldest first. Same single owner and thread as [closed]. */
    private val connections = mutableMapOf<NodeId, ArrayDeque<Connection>>()

    /** [id]'s newest open connection's room, or `null` when it has none. */
    fun newestRoom(id: NodeId): FakeRoom? = connections[id]?.lastOrNull()?.room

    override suspend fun start(onRoom: suspend (Room) -> Unit): Unit = coroutineScope {
        suspend fun admit(id: NodeId) {
            val room = roomFor(id)
            val admission = launch {
                try {
                    onRoom(room)
                } finally {
                    withContext(NonCancellable) { room.leave(LeaveReason.Normal) }
                }
            }
            connections.getOrPut(id) { ArrayDeque() }.addLast(Connection(room, admission))
        }
        initial.forEach { id ->
            admit(id)
            if (arrivalGap > Duration.ZERO) delay(arrivalGap)
        }
        for (command in commands) {
            when (command) {
                is Command.Connect -> admit(command.id)
                is Command.Close -> {
                    // A close with nothing to close is a mistyped test, not a no-op: fail it loudly.
                    val open = connections[command.id].orEmpty()
                    val connection = when (command.which) {
                        Which.Newest -> connections[command.id]?.removeLastOrNull()
                        Which.Oldest -> connections[command.id]?.removeFirstOrNull()
                    } ?: error("close ${command.which} for ${command.id}, which has ${open.size} open connections")
                    when (command.how) {
                        How.Tear -> {
                            connection.room.tearSeam()
                            connection.room.leave(LeaveReason.Normal)
                        }
                        How.Depart -> connection.room.removeMember(PeerId(command.id.value))
                    }
                    closed += command.id to connection
                }
            }
        }
    }

    private suspend fun roomFor(id: NodeId): FakeRoom = FakeRoom(
        selfId = self,
        initialRoster = setOf(
            Member(PeerId(id.value), MemberIdentity(displayName = id.value, sessionId = id.value), DeviceKey.generate().id, Liveness.Connected),
        ),
    )

    override fun close(): Unit = Unit
}
