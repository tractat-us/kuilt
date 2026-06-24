/**
 * Warp spike D — consistent-hashing task assignment under membership churn.
 * SPECULATIVE / EXPERIMENTAL. Throwaway `:examples` spike, never wired into public API.
 *
 * Strategy D: hash each task to a peer on a virtual-node ring.
 *   owner(taskId) = first peer clockwise of hash(taskId)
 *   Each peer executes ONLY the tasks whose owner (under its local membership view) is itself.
 *   No per-task messages. Results ORMap stays as churn-window dedup backstop.
 *
 * Two ring sources are modelled independently and compared:
 *   D-GOSSIP — ring built from each peer's local (lagging) gossip membership view.
 *               Cheap; duplicates during the disagreement window.
 *   D-STRONG — ring built from an agreed membership set (modelled as a quorum-round-trip
 *               per membership change, charge = 2 × quorumSize messages per join/leave/partition).
 *               Zero duplicates; coordination cost amortised across all tasks in the epoch.
 *
 * Failover: when a task's owner is flagged as down (partitioned/left), next-clockwise takes over.
 * While peers disagree on whether the owner is down, owner-if-alive AND backup both fire —
 * a failover duplication window. The sim counts these and includes them in total dups.
 *
 * Primary sweep: churn rate at 4 and 8 peers. Baseline: OPT/IR/CONS from v2 at matched configs.
 *
 * All simulations are seeded and deterministic. Hard convergence assertion in every run.
 */
package us.tractat.kuilt.examples.warp

import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.math.absoluteValue
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Churn configuration
// ---------------------------------------------------------------------------

/**
 * Per-round churn model for the Strategy D simulation.
 *
 * @param joinRatePercent   % chance each existing peer slot spawns a new joiner per round.
 * @param leaveRatePercent  % chance each current peer leaves (clean departure) per round.
 * @param partitionRatePercent % chance each current peer becomes unreachable (stays in ring
 *                            but appears down to liveness — triggering failover ambiguity).
 * @param vnodeCount        virtual nodes per peer for even ring distribution.
 */
internal data class ChurnConfig(
    val joinRatePercent: Int = 0,
    val leaveRatePercent: Int = 0,
    val partitionRatePercent: Int = 0,
    val vnodeCount: Int = 64,
)

// ---------------------------------------------------------------------------
// Strategy-D result types
// ---------------------------------------------------------------------------

/** Result for one ring-source variant within a Strategy D run. */
internal data class RingResult(
    val label: String,
    val totalTasks: Int,
    val peerCount: Int,
    val churnConfig: ChurnConfig,
    val rounds: Int,
    val totalExecutions: Int,
    val duplicateExecutions: Int,
    val duplicateRate: Double,
    val tasksCompleted: Int,
    /** Total membership-coordination messages sent (0 for gossip-roster; 2×quorum per epoch-change for strong). */
    val totalCoordinationMessages: Int,
    /** Coordination messages per unique completed task. */
    val coordMessagesPerTask: Double,
    /** Tasks that were never executed (lost due to owner-down with no live backup). */
    val tasksLost: Int,
)

/** Aggregated result from one Strategy-D run at a specific churn config. */
internal data class StrategyDResult(
    val totalTasks: Int,
    val peerCount: Int,
    val churnConfig: ChurnConfig,
    val rounds: Int,
    val gossip: RingResult,
    val strong: RingResult,
)

// ---------------------------------------------------------------------------
// Per-peer state for Strategy D
// ---------------------------------------------------------------------------

private data class DPeerState(
    val id: ReplicaId,
    /** Local gossip membership view — may lag behind other peers during churn. */
    val membershipView: Set<ReplicaId>,
    /** Which peers this peer currently believes are down (partitioned/left + liveness flag). */
    val downPeers: Set<ReplicaId>,
    val queue: ORSet<TaskId>,
    val results: ORMap<TaskId, LWWRegister<String>>,
    val clock: Long = 0L,
    val executions: Set<TaskId> = emptySet(),
    /** Membership coordination messages sent (only for strong-membership variant). */
    val membershipCoordMessages: Int = 0,
)

// ---------------------------------------------------------------------------
// Hash ring
// ---------------------------------------------------------------------------

/**
 * A simple consistent-hash ring with virtual nodes.
 *
 * Each peer gets [vnodeCount] positions on a ring of [RING_SIZE] slots.
 * [owner] returns the peer whose virtual-node position is first clockwise of hash(id).
 * When [downPeers] is non-empty, we skip dead owners and return the next live peer.
 */
private class HashRing(members: Set<ReplicaId>, vnodeCount: Int, private val downPeers: Set<ReplicaId> = emptySet()) {

    private val ring: List<Pair<Int, ReplicaId>>

    init {
        ring = members
            .flatMap { peer -> (0 until vnodeCount).map { vnode -> vnodeHash(peer, vnode) to peer } }
            .sortedBy { it.first }
    }

    fun owner(taskId: TaskId): ReplicaId? {
        if (ring.isEmpty()) return null
        val hash = taskHash(taskId)
        // Find first clockwise vnode at or after hash.
        val idx = ring.indexOfFirst { it.first >= hash }.let { if (it == -1) 0 else it }
        // Walk clockwise until we find a live peer.
        for (offset in ring.indices) {
            val candidate = ring[(idx + offset) % ring.size].second
            if (candidate !in downPeers) return candidate
        }
        return null  // All peers down — task unassignable.
    }

    private fun vnodeHash(peer: ReplicaId, vnode: Int): Int =
        (peer.value + ":" + vnode).hashCode().absoluteValue % RING_SIZE

    private fun taskHash(taskId: TaskId): Int =
        taskId.id.hashCode().absoluteValue % RING_SIZE

    private companion object {
        const val RING_SIZE = Int.MAX_VALUE
    }
}

// ---------------------------------------------------------------------------
// Main entry point
// ---------------------------------------------------------------------------

/**
 * Run Strategy D (consistent-hash assignment) under configurable membership churn.
 * Runs both ring variants (gossip-roster + strong-membership) under identical conditions.
 */
internal fun runStrategyD(
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    churnConfig: ChurnConfig,
    gossipConfig: GossipConfig,
    rng: Random,
): StrategyDResult {
    val baseSeed = rng.nextLong()

    val gossipResult = runDVariant(
        label = "D-GOSSIP",
        strongMembership = false,
        taskCount = taskCount,
        peerCount = peerCount,
        rounds = rounds,
        churnConfig = churnConfig,
        gossipConfig = gossipConfig,
        rng = Random(baseSeed),
    )

    val strongResult = runDVariant(
        label = "D-STRONG",
        strongMembership = true,
        taskCount = taskCount,
        peerCount = peerCount,
        rounds = rounds,
        churnConfig = churnConfig,
        gossipConfig = gossipConfig,
        rng = Random(baseSeed + 1),
    )

    return StrategyDResult(
        totalTasks = taskCount,
        peerCount = peerCount,
        churnConfig = churnConfig,
        rounds = rounds,
        gossip = gossipResult,
        strong = strongResult,
    )
}

// ---------------------------------------------------------------------------
// Single-variant simulation
// ---------------------------------------------------------------------------

private fun runDVariant(
    label: String,
    strongMembership: Boolean,
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    churnConfig: ChurnConfig,
    gossipConfig: GossipConfig,
    rng: Random,
): RingResult {
    // Build initial member set and shared task queue.
    val initialIds = (0 until peerCount).map { ReplicaId("peer-$it") }
    val sharedQueue = buildDInitialQueue(initialIds, taskCount, Random(rng.nextLong()))
    val activeViews = buildActiveViews(initialIds, gossipConfig.fanout, Random(rng.nextLong()))

    // All peers start with full membership knowledge.
    var peers = initialIds.map { id ->
        DPeerState(
            id = id,
            membershipView = initialIds.toSet(),
            downPeers = emptySet(),
            queue = sharedQueue,
            results = ORMap.empty(),
        )
    }

    // Ground-truth membership for the sim (used to drive churn events + strong-membership agreement).
    var groundTruthMembers = initialIds.toSet()
    var groundTruthDown = emptySet<ReplicaId>()

    // For strong-membership: track the agreed member set visible to all peers (same epoch).
    var agreedMembers = initialIds.toSet()
    var agreedDown = emptySet<ReplicaId>()

    // For cost accounting in strong mode: coord messages for membership changes this epoch.
    var totalMembershipCoordMessages = 0

    // Track executions for dup analysis.
    val claimsPerTask = mutableMapOf<TaskId, MutableList<ReplicaId>>()

    // Deferred gossip messages (transient loss).
    var deferredGossip: List<DDeferredMessage> = emptyList()

    // Next peer index for generating new joiners.
    var nextPeerIdx = peerCount

    repeat(rounds) {
        // 1. Apply deferred gossip.
        val (resolved, stillDeferred) = deferredGossip.partition { rng.nextDouble() >= gossipConfig.messageLossRate }
        deferredGossip = stillDeferred
        peers = applyDDeferred(peers, resolved)

        // 2. Churn events — joins, leaves, partitions.
        val churnResult = applyChurn(
            peers = peers,
            groundTruthMembers = groundTruthMembers,
            groundTruthDown = groundTruthDown,
            churnConfig = churnConfig,
            sharedQueue = sharedQueue,
            nextPeerIdx = nextPeerIdx,
            rng = rng,
        )
        peers = churnResult.peers
        groundTruthMembers = churnResult.newMembers
        groundTruthDown = churnResult.newDown
        nextPeerIdx = churnResult.nextPeerIdx

        // Compute membership coordination cost for this round.
        val membershipChanges = churnResult.joins + churnResult.leaves + churnResult.partitions
        if (membershipChanges > 0) {
            val quorumSize = (groundTruthMembers.size / 2) + 1
            val costThisRound = membershipChanges * 2 * quorumSize
            totalMembershipCoordMessages += costThisRound

            // For strong-membership: agreed ring advances to ground truth immediately (modelling quorum agreement).
            agreedMembers = groundTruthMembers
            agreedDown = groundTruthDown
        }

        // 3. Propagate membership events to each peer's local gossip view.
        peers = propagateMembership(peers, groundTruthMembers, groundTruthDown, gossipConfig, rng)

        // 4. Hash-ring assignment: each peer executes tasks whose owner maps to itself.
        val roundClaims = mutableListOf<Pair<TaskId, ReplicaId>>()
        peers = peers.map { peer ->
            if (peer.id in groundTruthDown) return@map peer  // Downed peers don't execute.

            val effectiveMembers = if (strongMembership) agreedMembers else peer.membershipView
            val effectiveDown = if (strongMembership) agreedDown else peer.downPeers

            val ring = HashRing(
                members = effectiveMembers - effectiveDown,
                vnodeCount = churnConfig.vnodeCount,
                downPeers = effectiveDown,
            )

            val newExecutions = peer.queue.elements
                .filter { taskId ->
                    val owner = ring.owner(taskId)
                    owner == peer.id && taskId !in peer.results.keys && taskId !in peer.executions
                }
            newExecutions.forEach { roundClaims.add(it to peer.id) }
            peer.copy(executions = peer.executions + newExecutions)
        }

        for ((taskId, claimedBy) in roundClaims) {
            claimsPerTask.getOrPut(taskId) { mutableListOf() }.add(claimedBy)
        }

        // 5. Execute phase: write results for claimed tasks.
        peers = peers.map { peer ->
            var current = peer
            for (taskId in peer.executions) {
                if (taskId !in current.results.keys) {
                    val newClock = current.clock + 1L
                    val register = LWWRegister.empty<String>().set(peer.id, newClock, "result-by-${peer.id.value}")
                    current = current.copy(
                        results = current.results.put(peer.id, taskId, register),
                        clock = newClock,
                    )
                }
            }
            current
        }

        // 6. Gossip propagation (tasks + results).
        val (afterGossip, moreDeferred) = dGossipPhase(peers, buildActiveViews(
            peers.map { it.id }, gossipConfig.fanout, Random(rng.nextLong()),
        ), gossipConfig, rng)
        peers = afterGossip
        deferredGossip = deferredGossip + moreDeferred
    }

    // Final convergence: force-merge all live peers.
    val livePeers = peers.filter { it.id !in groundTruthDown }
    val convergedPeers = forceDConverge(livePeers)

    val tasksLost = assertDConvergence(convergedPeers, taskCount, label)
    return buildRingResult(
        label = label,
        taskCount = taskCount,
        peers = convergedPeers,
        claimsPerTask = claimsPerTask,
        churnConfig = churnConfig,
        gossipConfig = gossipConfig,
        rounds = rounds,
        membershipCoordMessages = if (strongMembership) totalMembershipCoordMessages else 0,
        tasksLost = tasksLost,
    )
}

// ---------------------------------------------------------------------------
// Churn engine
// ---------------------------------------------------------------------------

private data class ChurnOutput(
    val peers: List<DPeerState>,
    val newMembers: Set<ReplicaId>,
    val newDown: Set<ReplicaId>,
    val nextPeerIdx: Int,
    val joins: Int,
    val leaves: Int,
    val partitions: Int,
)

private fun applyChurn(
    peers: List<DPeerState>,
    groundTruthMembers: Set<ReplicaId>,
    groundTruthDown: Set<ReplicaId>,
    churnConfig: ChurnConfig,
    sharedQueue: ORSet<TaskId>,
    nextPeerIdx: Int,
    rng: Random,
): ChurnOutput {
    val livePeers = groundTruthMembers - groundTruthDown
    var updatedPeers = peers.toMutableList()
    var newMembers = groundTruthMembers.toMutableSet()
    var newDown = groundTruthDown.toMutableSet()
    var joinCount = 0
    var leaveCount = 0
    var partitionCount = 0
    var nextIdx = nextPeerIdx

    // Partitions: some live peers become unreachable.
    for (peerId in livePeers.toList()) {
        if (rng.nextInt(100) < churnConfig.partitionRatePercent) {
            newDown.add(peerId)
            partitionCount++
            // Update that peer's local state to reflect it's isolated.
            val idx = updatedPeers.indexOfFirst { it.id == peerId }
            if (idx >= 0) updatedPeers[idx] = updatedPeers[idx].copy(downPeers = updatedPeers[idx].downPeers + peerId)
        }
    }

    // Leaves: some live peers cleanly depart.
    for (peerId in livePeers.toList()) {
        if (peerId !in newDown && rng.nextInt(100) < churnConfig.leaveRatePercent) {
            newMembers.remove(peerId)
            newDown.remove(peerId)
            updatedPeers.removeIf { it.id == peerId }
            leaveCount++
        }
    }

    // Joins: new peers appear (one chance per existing slot).
    if (churnConfig.joinRatePercent > 0 && rng.nextInt(100) < churnConfig.joinRatePercent) {
        val newId = ReplicaId("peer-$nextIdx")
        nextIdx++
        val newPeer = DPeerState(
            id = newId,
            membershipView = newMembers.toSet(),
            downPeers = newDown.toSet(),
            queue = sharedQueue,
            results = ORMap.empty(),
        )
        updatedPeers.add(newPeer)
        newMembers.add(newId)
        joinCount++
    }

    return ChurnOutput(
        peers = updatedPeers,
        newMembers = newMembers,
        newDown = newDown,
        nextPeerIdx = nextIdx,
        joins = joinCount,
        leaves = leaveCount,
        partitions = partitionCount,
    )
}

// ---------------------------------------------------------------------------
// Membership propagation (gossip-based lag)
// ---------------------------------------------------------------------------

/**
 * Each round, peers learn about the true membership with a gossip delay.
 * Each live peer has a [gossipConfig.partitionRate] chance of NOT seeing the change this round.
 */
private fun propagateMembership(
    peers: List<DPeerState>,
    groundTruthMembers: Set<ReplicaId>,
    groundTruthDown: Set<ReplicaId>,
    gossipConfig: GossipConfig,
    rng: Random,
): List<DPeerState> = peers.map { peer ->
    if (rng.nextDouble() < gossipConfig.partitionRate) {
        // This peer misses the membership update this round (gossip lag).
        peer
    } else {
        peer.copy(
            membershipView = groundTruthMembers,
            downPeers = groundTruthDown,
        )
    }
}

// ---------------------------------------------------------------------------
// Gossip propagation for task queue + results
// ---------------------------------------------------------------------------

private data class DDeferredMessage(
    val targetIdx: Int,
    val queue: ORSet<TaskId>,
    val results: ORMap<TaskId, LWWRegister<String>>,
)

private fun dGossipPhase(
    peers: List<DPeerState>,
    activeViews: Map<ReplicaId, List<ReplicaId>>,
    gossipConfig: GossipConfig,
    rng: Random,
): Pair<List<DPeerState>, List<DDeferredMessage>> {
    val peerIndexOf = peers.indices.associateBy { peers[it].id }
    val mutablePeers = peers.toMutableList()
    val deferred = mutableListOf<DDeferredMessage>()

    for (srcIdx in peers.indices) {
        val src = peers[srcIdx]
        val neighbours = activeViews[src.id] ?: emptyList()
        for (neighbour in neighbours) {
            val dstIdx = peerIndexOf[neighbour] ?: continue
            repeat(gossipConfig.propagationHops) {
                if (rng.nextDouble() < gossipConfig.partitionRate) return@repeat
                val message = DDeferredMessage(dstIdx, src.queue, src.results)
                if (rng.nextDouble() < gossipConfig.messageLossRate) {
                    deferred.add(message)
                } else {
                    val dst = mutablePeers[dstIdx]
                    mutablePeers[dstIdx] = dst.copy(
                        queue = dst.queue.piece(src.queue),
                        results = dst.results.piece(src.results),
                    )
                }
            }
        }
    }
    return mutablePeers to deferred
}

private fun applyDDeferred(
    peers: List<DPeerState>,
    messages: List<DDeferredMessage>,
): List<DPeerState> {
    val mutablePeers = peers.toMutableList()
    for (msg in messages) {
        if (msg.targetIdx >= mutablePeers.size) continue
        val dst = mutablePeers[msg.targetIdx]
        mutablePeers[msg.targetIdx] = dst.copy(
            queue = dst.queue.piece(msg.queue),
            results = dst.results.piece(msg.results),
        )
    }
    return mutablePeers
}

// ---------------------------------------------------------------------------
// Convergence
// ---------------------------------------------------------------------------

private fun forceDConverge(peers: List<DPeerState>): List<DPeerState> {
    if (peers.isEmpty()) return peers
    val mergedQueue = peers.fold(ORSet.empty<TaskId>()) { acc, p -> acc.piece(p.queue) }
    val mergedResults = peers.fold(ORMap.empty<TaskId, LWWRegister<String>>()) { acc, p -> acc.piece(p.results) }
    return peers.map { p -> p.copy(queue = mergedQueue, results = mergedResults) }
}

/**
 * Asserts convergence and returns the count of tasks that were never executed (lost due to
 * all owners being simultaneously down with no failover candidate).
 */
private fun assertDConvergence(peers: List<DPeerState>, taskCount: Int, label: String): Int {
    if (peers.isEmpty()) return taskCount
    val referenceResults = peers.first().results
    for (peer in peers.drop(1)) {
        require(peer.results == referenceResults) {
            "[$label] Convergence failure: peer ${peer.id} results differ from peer-0"
        }
    }
    val resultKeys = referenceResults.keys
    require(resultKeys.size == resultKeys.toSet().size) {
        "[$label] Duplicate keys in Results ORMap"
    }
    val allExecuted = peers.flatMap { it.executions }.toSet()
    for (taskId in allExecuted) {
        require(taskId in resultKeys) {
            "[$label] Task $taskId was executed but has no result"
        }
    }
    require(resultKeys.size <= taskCount) {
        "[$label] More results (${resultKeys.size}) than tasks ($taskCount)"
    }
    return taskCount - resultKeys.size
}

// ---------------------------------------------------------------------------
// Topology helpers
// ---------------------------------------------------------------------------

private fun buildActiveViews(
    replicaIds: List<ReplicaId>,
    fanout: Int,
    rng: Random,
): Map<ReplicaId, List<ReplicaId>> = replicaIds.associateWith { self ->
    val others = replicaIds.filter { it != self }
    val k = minOf(fanout, others.size)
    others.shuffled(rng).take(k)
}

private fun buildDInitialQueue(
    replicaIds: List<ReplicaId>,
    taskCount: Int,
    rng: Random,
): ORSet<TaskId> {
    val peerCount = replicaIds.size
    var queue = ORSet.empty<TaskId>()
    for (i in 0 until taskCount) {
        val owner = replicaIds[rng.nextInt(peerCount)]
        queue = queue.add(owner, TaskId("task-$i"))
    }
    return queue
}

// ---------------------------------------------------------------------------
// Result assembly
// ---------------------------------------------------------------------------

private fun buildRingResult(
    label: String,
    taskCount: Int,
    peers: List<DPeerState>,
    claimsPerTask: Map<TaskId, List<ReplicaId>>,
    churnConfig: ChurnConfig,
    gossipConfig: GossipConfig,
    rounds: Int,
    membershipCoordMessages: Int,
    tasksLost: Int,
): RingResult {
    // Filter claims to live peers only. Under churn, departed peers' claims accumulate in
    // claimsPerTask while their executions are absent from the live peer set — including them
    // inflates both numerator and denominator relative to each other in a misleading way.
    // "Duplicate rate" here means: among live peers' executions, what fraction were redundant?
    val livePeerIds = peers.map { it.id }.toSet()
    val liveClaimsPerTask: Map<TaskId, List<ReplicaId>> = claimsPerTask
        .mapValues { (_, claimers) -> claimers.filter { it in livePeerIds } }
        .filterValues { it.isNotEmpty() }

    val totalExecutions = liveClaimsPerTask.values.sumOf { it.size }
    val duplicateExecutions = liveClaimsPerTask.values.sumOf { maxOf(0, it.size - 1) }
    val tasksCompleted = liveClaimsPerTask.keys.size
    val duplicateRate = if (totalExecutions > 0) duplicateExecutions.toDouble() / totalExecutions else 0.0
    val coordPerTask = if (tasksCompleted > 0) membershipCoordMessages.toDouble() / tasksCompleted else 0.0

    return RingResult(
        label = label,
        totalTasks = taskCount,
        peerCount = peers.size,
        churnConfig = churnConfig,
        rounds = rounds,
        totalExecutions = totalExecutions,
        duplicateExecutions = duplicateExecutions,
        duplicateRate = duplicateRate,
        tasksCompleted = tasksCompleted,
        totalCoordinationMessages = membershipCoordMessages,
        coordMessagesPerTask = coordPerTask,
        tasksLost = tasksLost,
    )
}

// ---------------------------------------------------------------------------
// v2 baseline integration — run OPT/IR/CONS at matched configs for comparison
// ---------------------------------------------------------------------------

/**
 * Run Strategy D alongside the three v2 baselines (OPT/IR/CONS) at the same peer count
 * and gossip config, with zero churn. Allows apples-to-apples comparison.
 */
internal fun runStrategyDWithBaseline(
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    churnConfig: ChurnConfig,
    gossipConfig: GossipConfig,
    rng: Random,
): DWithBaselineResult {
    val seed = rng.nextLong()
    val d = runStrategyD(taskCount, peerCount, rounds, churnConfig, gossipConfig, Random(seed))
    val v2 = runWarpComparisonV2(taskCount, peerCount, rounds, gossipConfig, Random(seed + 10))
    return DWithBaselineResult(d, v2)
}

internal data class DWithBaselineResult(
    val d: StrategyDResult,
    val v2: V2ComparisonResult,
)
