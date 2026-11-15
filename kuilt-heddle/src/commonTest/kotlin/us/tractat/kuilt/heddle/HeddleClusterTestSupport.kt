package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Shared multi-peer rigs for `:kuilt-heddle`'s tests (`.claude/rules/consensus.md`: one harness,
// never a second hand-rolled one). [staticCluster] is the static-bootstrap rig `HeddleNodeTest`
// grew; [governedCluster] puts governed nodes on `MultiNodeRaftSim` from `:kuilt-raft-test`.
// Both run on the test's virtual clock and are advanced by bounded `pump`s only, never
// `advanceUntilIdle`: the replicator and liveness timers re-arm forever.

/** The config every rig here boots with: a quick anti-entropy round, and one seeded RNG per peer. */
internal fun heddleTestConfig(seed: Int, quantum: Long = 10L, cap: Long = 1_000L): HeddleConfig = HeddleConfig(
    policy = PolicyConfig(quantum = quantum),
    maxHoldingsPerPeer = cap,
    demandTtl = 30.seconds,
    quilter = QuilterConfig(
        antiEntropyInterval = 100.milliseconds,
        fullStateRetryLimit = 0,
        expectVirtualTime = true,
    ),
    heartbeat = HeartbeatConfig(),
    random = Random(seed),
)

/** The data-plane surface a workload drives, the same on a static [HeddleNode] and a [GovernedHeddleNode]. */
internal class SchedulingPeer(
    val self: ReplicaId,
    val ledger: StateFlow<EntitlementLedger>,
    val execution: FairShareExecution,
    val advertise: (AttachmentId, Demand) -> Unit,
    val schedule: (GroupId) -> ScheduleOutcome,
    val boundMetrics: (GroupId) -> BoundMetrics,
    val rebases: () -> Int,
)

internal fun HeddleNode.asSchedulingPeer(): SchedulingPeer =
    SchedulingPeer(self, ledger, this, ::advertise, ::schedule, ::boundMetrics) { rebasesForTest }

internal fun GovernedHeddleNode.asSchedulingPeer(): SchedulingPeer =
    SchedulingPeer(self, ledger, this, ::advertise, ::schedule, ::boundMetrics) { rebasesForTest }

/**
 * A long scheduling workload at one parent's [lanes]: each call draws a fresh waiting set, gives
 * every waiting lane a demand of one [grant] more and every other lane none, schedules, then
 * reserves whatever each lane holds here and completes it charged below the reservation.
 *
 * The draw walks [lanes], a [List] the caller built, so a seed takes the same trajectory on every
 * target (AGENTS.md, *A seeded generator must impose its own total order*).
 */
internal class MixedWeightRun(
    private val root: GroupId,
    private val lanes: List<AttachmentRecord>,
    private val grant: Long,
    private val random: Random,
) {
    var calls: Int = 0
        private set
    var grants: Int = 0
        private set
    var chargedBelowReservation: Int = 0
        private set

    /** The next waiting set: each lane with even odds, and never empty. */
    fun drawWaiting(): List<AttachmentRecord> =
        lanes.filter { random.nextBoolean() }.ifEmpty { listOf(lanes[random.nextInt(lanes.size)]) }

    /** Give every lane in [waiting] one [grant] more than it has outstanding, and every other lane nothing. */
    fun advertise(peer: SchedulingPeer, waiting: List<AttachmentRecord>) {
        for (lane in lanes) {
            val outstanding = peer.ledger.value.edge(lane.id)?.outstanding ?: 0L
            peer.advertise(lane.id, if (lane in waiting) Demand(outstanding + grant, grant) else Demand.NONE)
        }
    }

    /** Schedule once at [root], counting the call and its grants. */
    fun schedule(peer: SchedulingPeer): ScheduleOutcome {
        calls++
        val outcome = peer.schedule(root)
        if (outcome is ScheduleOutcome.Delegated) grants += outcome.grants
        return outcome
    }

    /** Spend what each lane holds on this peer, charging a little over half of each reservation. */
    fun chargeBelowReservation(peer: SchedulingPeer) {
        for (lane in lanes) {
            val held = peer.ledger.value.holdings(lane.child, peer.self)
            if (held < 2L) continue
            val reserved = peer.execution.reserve(lane.child, held) as? ReserveOutcome.Reserved ?: continue
            peer.execution.complete(reserved.id, held / 2L + 1L)
            chargedBelowReservation++
        }
    }

    /** One whole call: draw, advertise, schedule, charge. */
    fun step(peer: SchedulingPeer): ScheduleOutcome {
        advertise(peer, drawWaiting())
        val outcome = schedule(peer)
        chargeBelowReservation(peer)
        return outcome
    }

    /** Each lane's issued total, in [lanes] order: what a call's picks changed. */
    fun issuedByLane(peer: SchedulingPeer): List<Long> = lanes.map { peer.ledger.value.edge(it.id)?.issued ?: 0L }
}

/**
 * What breaks the §10.1 identity at one level under [root], or `null` when it holds: every unit
 * ever minted is held at [root] or one of its active children by one of [replicas], or spent at a
 * leaf. The sum balances through a negative holding, so a conflict [EntitlementLedger.validate]
 * reports breaks it too.
 */
internal fun EntitlementLedger.conservationShortfall(root: GroupId, replicas: Collection<ReplicaId>): String? {
    val conflicts = validate()
    if (conflicts.isNotEmpty()) return "the ledger reports $conflicts"
    val groups = listOf(root) + activeChildren(root).map { it.child }
    var held = 0L
    for (g in groups) for (r in replicas) held = checkNotNull(held.plusOrNull(holdings(g, r))) { "held overflows" }
    val accounted = checkNotNull(held.plusOrNull(leafSpentTotal())) { "held + spent overflows" }
    return if (accounted == mintedTotal()) null else "held $held + spent ${leafSpentTotal()} != minted ${mintedTotal()}"
}

/** The root every governed rig here bootstraps under. */
internal val ACCEPTANCE_ROOT: GroupId = GroupId("root")

// ─────────────────────────────────────────────────────────────────────────────
// The static rig: peers bootstrapped with `heddleStatic`, no consensus log.
// ─────────────────────────────────────────────────────────────────────────────

internal class StaticPeer(val id: ReplicaId, val node: HeddleNode, val gate: GatedSeam)

/** Peers on one [InMemoryLoom], each behind a [GatedSeam] so a test can cut it off and heal it. */
internal class StaticCluster(val peers: List<StaticPeer>, private val scheduler: TestScope) {
    /** Partition peer [i] from everyone (drops its traffic both ways). */
    fun partition(i: Int) {
        peers[i].gate.connected.value = false
    }

    /** Heal peer [i]'s link. */
    fun heal(i: Int) {
        peers[i].gate.connected.value = true
    }

    /** Advance bounded virtual time and flush — never `advanceUntilIdle`. */
    fun pump(ms: Long = 400L) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }
}

/**
 * [peers] static nodes under [root], peer `i` minted `mint[i]` at the root and every peer
 * bootstrapped with [topology]. [wrap] wraps each peer's seam before the gate, to put a fabric
 * fault (duplication, loss) under it.
 */
internal suspend fun TestScope.staticCluster(
    root: GroupId,
    peers: Int,
    mint: Map<Int, Long>,
    topology: List<AttachmentRecord>,
    wrap: (Seam) -> Seam = { it },
    quantum: Long = 10L,
): StaticCluster {
    val loom = InMemoryLoom()
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val seams: List<Seam> = (0 until peers).map { i ->
        if (i == 0) loom.host(Pattern("heddle-e2e")) else loom.join(InMemoryTag("peer-$i"))
    }
    val mintById: Map<ReplicaId, Long> = seams.mapIndexed { i, s -> ReplicaId(s.selfId.value) to (mint[i] ?: 0L) }.toMap()
    val nodes = seams.mapIndexed { i, seam ->
        val gate = GatedSeam(wrap(seam))
        val self = ReplicaId(seam.selfId.value)
        val node = backgroundScope.heddleStatic(
            seam = gate,
            self = self,
            root = root,
            mint = mintById,
            topology = topology,
            clock = clock,
            config = heddleTestConfig(seed = 1_000 + i, quantum = quantum),
            epoch = 0L,
        )
        StaticPeer(self, node, gate)
    }
    return StaticCluster(nodes, this)
}

// ─────────────────────────────────────────────────────────────────────────────
// The governed rig: governed nodes on `MultiNodeRaftSim`.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One governed peer: its Raft node in the cluster's [MultiNodeRaftSim], its [node], the gate on
 * its data-plane seam, and the scope its coroutines live on, which [GovernedCluster.restart] cancels.
 */
internal class GovernedPeer(
    val raftId: NodeId,
    val node: GovernedHeddleNode,
    val gate: GatedSeam,
    val scope: CoroutineScope,
) {
    val replica: ReplicaId get() = node.self
}

/**
 * Governed peers, one per Raft node of [sim], on one data-plane [InMemoryLoom]: the wiring
 * `HeddleFenceTest` gives a governed node, with a real data plane on every peer. Control verbs go
 * through [applied], which pumps the cluster until the act commits.
 */
internal class GovernedCluster(
    val sim: MultiNodeRaftSim,
    private val test: TestScope,
    private val loom: InMemoryLoom,
    private val quantum: Long,
    initial: List<GovernedPeer>,
) {
    private val live = initial.toMutableList()

    /** The peers now running, in boot order; a restarted peer keeps its index. */
    val peers: List<GovernedPeer> get() = live

    /** Every replica that has ever run here, a restarted one's old identity included: what conservation sums over. */
    val everyReplica: MutableSet<ReplicaId> = initial.mapTo(LinkedHashSet()) { it.replica }

    private var incarnations = 0

    /** Run [block] on the test's background scope, pump the cluster until it returns, and require it Applied. */
    suspend fun applied(what: String, block: suspend () -> ControlOutcome): ControlOutcome.Applied {
        val outcome = test.backgroundScope.async { block() }
        sim.awaitTrue(what) { outcome.isCompleted }
        val done = outcome.await()
        check(done is ControlOutcome.Applied) { "$what: expected Applied, got $done" }
        return done
    }

    /** Enroll every peer, mint `mint[i]` to peer `i`, and prepare and activate [topology], all through the log. */
    suspend fun bootstrap(topology: List<AttachmentRecord>, mint: Map<Int, Long>) {
        for (p in live) applied("${p.replica} enrolls") { p.node.enroll(p.replica) }
        for ((i, amount) in mint) applied("mint to peer $i") { live[i].node.mint(live[i].replica, amount) }
        val proposer = live[0].node
        for (record in topology) {
            applied("prepare ${record.id}") { proposer.prepare(record) }
            applied("activate ${record.id}") { proposer.activate(record.id) }
        }
        pump()
    }

    /** Advance bounded virtual time and flush — never `advanceUntilIdle`. */
    fun pump(ms: Long = 400L) {
        test.advanceTimeBy(ms)
        test.runCurrent()
    }

    /** Cut peer [i]'s data plane off from everyone; its Raft node stays connected. */
    fun partitionDataPlane(i: Int) {
        live[i].gate.connected.value = false
    }

    /** Heal peer [i]'s data plane. */
    fun healDataPlane(i: Int) {
        live[i].gate.connected.value = true
    }

    /**
     * Crash peer [i], its Raft node included, and boot it again from that node's durable log under a
     * fresh incarnation and a fresh data-plane seam. The fabric gives the new seam a new address, so
     * the restarted process is a new replica: it enrolls itself, and what the old one held stays
     * where it was (a crashed peer's holdings are stranded, design §8.1).
     */
    suspend fun restart(i: Int) {
        val old = live[i]
        old.scope.cancel()
        old.gate.close(CloseReason.Normal)
        sim.crash(old.raftId)
        pump(1L)
        sim.restart(old.raftId)
        val fresh = boot(test, sim, loom, old.raftId, joinAt = 100 + incarnations++, quantum = quantum)
        live[i] = fresh
        everyReplica += fresh.replica
        applied("${fresh.replica} enrolls after its restart") { fresh.node.enroll(fresh.replica) }
    }

    companion object {
        /** Boot the governed peer for [raftId] on [loom], hosting it when [joinAt] is zero and joining it otherwise. */
        suspend fun boot(
            test: TestScope,
            sim: MultiNodeRaftSim,
            loom: InMemoryLoom,
            raftId: NodeId,
            joinAt: Int,
            quantum: Long,
        ): GovernedPeer {
            val scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
            val seam = if (joinAt == 0) loom.host(Pattern("heddle-governed")) else loom.join(InMemoryTag("governed-$joinAt"))
            val gate = GatedSeam(seam)
            val node = scope.heddleGoverned(
                seam = gate,
                self = ReplicaId(seam.selfId.value),
                raft = sim.nodes.getValue(raftId),
                root = ACCEPTANCE_ROOT,
                clock = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) },
                config = heddleTestConfig(seed = 2_000 + joinAt, quantum = quantum),
                incarnation = "${raftId.value}-$joinAt",
                epoch = joinAt.toLong(),
            )
            return GovernedPeer(raftId, node, gate, scope)
        }
    }
}

/** [size] governed peers on a fresh [MultiNodeRaftSim], with a leader elected. Bootstrap them with [GovernedCluster.bootstrap]. */
internal suspend fun TestScope.governedCluster(size: Int, quantum: Long): GovernedCluster {
    val ids = (1..size).map { NodeId("g$it") }
    val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
    val loom = InMemoryLoom()
    val peers = ids.mapIndexed { i, id -> GovernedCluster.boot(this, sim, loom, id, joinAt = i, quantum = quantum) }
    sim.awaitLeader()
    return GovernedCluster(sim, this, loom, quantum, peers)
}

// ─────────────────────────────────────────────────────────────────────────────
// Seam wrappers.
// ─────────────────────────────────────────────────────────────────────────────

/** A togglable seam wrapper: when disconnected, incoming is filtered out and sends are dropped. */
internal class GatedSeam(private val delegate: Seam) : Seam {
    val connected = MutableStateFlow(true)
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val incoming: Flow<Swatch> get() = delegate.incoming.filter { connected.value }
    override suspend fun broadcast(payload: ByteArray) {
        if (connected.value) delegate.broadcast(payload)
    }
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (connected.value) delegate.sendTo(peer, payload)
    }
    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}

/** A seam wrapper that delivers every frame twice, in and out — a duplicating fabric (§10.7). */
internal class DuplicatingSeam(private val delegate: Seam) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val incoming: Flow<Swatch>
        get() = delegate.incoming.transform {
            emit(it)
            emit(it)
        }
    override suspend fun broadcast(payload: ByteArray) {
        delegate.broadcast(payload)
        delegate.broadcast(payload)
    }
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        delegate.sendTo(peer, payload)
        delegate.sendTo(peer, payload)
    }
    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}
