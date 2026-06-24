/**
 * Warp spike D — consistent-hashing task assignment under membership churn.
 * SPECULATIVE / EXPERIMENTAL. Throwaway `:examples` spike, never wired into public API.
 *
 * Strategy D: hash each task to a peer on a virtual-node ring.
 *   owner(taskId) = first peer clockwise of hash(taskId)
 *   Each peer executes ONLY the tasks whose owner (under its local membership view) is itself.
 *   No per-task messages. Results ORMap stays as churn-window dedup backstop.
 *
 * v4 fix: membership deltas propagate through the SAME partial-view gossip path as task/result
 * deltas — same fanout, same propagationHops, same per-hop partitionRate/messageLossRate.
 * Peers therefore transiently disagree on the member set for a bounded convergence window,
 * compute divergent rings, and the same task can hash to different owners → real disagreement-
 * window duplicates appear and are caught by the ORMap backstop.
 *
 * Membership is modelled as two gossip-replicated ORSets:
 *   memberJoins  — set of peers that have been seen to join (grows only)
 *   memberLeaves — set of peers that have cleanly left (grows only, like a tombstone set)
 *   partitioned  — set of peers flagged as unreachable (liveness signal; also gossip-propagated)
 *   effective membership = (memberJoins.elements - memberLeaves.elements)
 *
 * Two ring sources:
 *   D-GOSSIP — ring from each peer's local (lagging) gossip membership view.
 *   D-STRONG — ring from an agreed member set (quorum round-trip per membership change).
 *              Zero dups; charge 2 × quorumSize messages per join/leave/partition event.
 *
 * Primary sweep: churn rate × propagationHops (gossip convergence lag) at 4 and 8 peers.
 * All simulations are seeded and deterministic. Hard convergence assertion in every run.
 */
package us.tractat.kuilt.examples.warp

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
 * @param joinRatePercent      % chance a new joiner appears per round (per existing-peer slot).
 * @param leaveRatePercent     % chance each current live peer departs cleanly per round.
 * @param partitionRatePercent % chance each current live peer becomes unreachable per round.
 * @param vnodeCount           virtual nodes per peer for even ring distribution.
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
    val gossipConfig: GossipConfig,
    val rounds: Int,
    val totalExecutions: Int,
    val duplicateExecutions: Int,
    val duplicateRate: Double,
    val tasksCompleted: Int,
    /** Total membership-coordination messages (0 for gossip-roster; 2×quorum per change for strong). */
    val totalCoordinationMessages: Int,
    /** Coordination messages per unique completed task. */
    val coordMessagesPerTask: Double,
    /** Tasks never executed — all possible owners simultaneously down or departed. */
    val tasksLost: Int,
)

/** Aggregated result from one Strategy-D run at a specific churn config. */
internal data class StrategyDResult(
    val totalTasks: Int,
    val peerCount: Int,
    val churnConfig: ChurnConfig,
    val gossipConfig: GossipConfig,
    val rounds: Int,
    val gossip: RingResult,
    val strong: RingResult,
)

// ---------------------------------------------------------------------------
// Per-peer state for Strategy D
// ---------------------------------------------------------------------------

private data class DPeerState(
    val id: ReplicaId,
    /**
     * Gossip-replicated join set — each peer that has ever joined is added here by the joiner.
     * Propagates through the same partial-view gossip path as task/result deltas.
     */
    val memberJoins: ORSet<String>,
    /**
     * Gossip-replicated leave set — when a peer cleanly departs, its id is added here.
     * Propagates identically to memberJoins.
     */
    val memberLeaves: ORSet<String>,
    /**
     * Gossip-replicated partition set — peers flagged as unreachable.
     * A peer in both memberJoins and partitioned: present on ring but skipped for failover.
     */
    val partitioned: ORSet<String>,
    val queue: ORSet<TaskId>,
    val results: ORMap<TaskId, LWWRegister<String>>,
    val clock: Long = 0L,
    val executions: Set<TaskId> = emptySet(),
) {
    /** Effective live membership as this peer currently sees it. */
    val effectiveMembers: Set<ReplicaId>
        get() = (memberJoins.elements - memberLeaves.elements).map { ReplicaId(it) }.toSet()

    /** Peers this peer considers down (partitioned and not yet known to have left). */
    val downPeers: Set<ReplicaId>
        get() = (partitioned.elements - memberLeaves.elements).map { ReplicaId(it) }.toSet()
}

// ---------------------------------------------------------------------------
// Hash ring
// ---------------------------------------------------------------------------

/**
 * A simple consistent-hash ring with virtual nodes.
 *
 * Each peer gets [vnodeCount] positions on a ring of [RING_SIZE] slots.
 * [owner] returns the first live peer clockwise of hash(taskId), skipping [downPeers].
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
        val startIdx = ring.indexOfFirst { it.first >= hash }.let { if (it == -1) 0 else it }
        for (offset in ring.indices) {
            val candidate = ring[(startIdx + offset) % ring.size].second
            if (candidate !in downPeers) return candidate
        }
        return null
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
 * Runs both ring variants (D-GOSSIP + D-STRONG) under identical conditions.
 */
internal fun runStrategyD(
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    churnConfig: ChurnConfig,
    gossipConfig: GossipConfig,
    rng: Random,
    tasksPerRound: Int = 0,
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
        tasksPerRound = tasksPerRound,
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
        tasksPerRound = tasksPerRound,
    )

    return StrategyDResult(
        totalTasks = taskCount,
        peerCount = peerCount,
        churnConfig = churnConfig,
        gossipConfig = gossipConfig,
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
    tasksPerRound: Int = 0,
): RingResult {
    val initialIds = (0 until peerCount).map { ReplicaId("peer-$it") }
    val sharedQueue = buildDInitialQueue(initialIds, taskCount, Random(rng.nextLong()))
    var totalTaskCount = taskCount
    var nextTaskIdx = taskCount

    // Seed the initial join ORSet — all initial peers are in it, added by peer-0.
    val initialJoins = initialIds.fold(ORSet.empty<String>()) { acc, id ->
        acc.add(initialIds[0], id.value)
    }

    var peers = initialIds.map { id ->
        DPeerState(
            id = id,
            memberJoins = initialJoins,
            memberLeaves = ORSet.empty(),
            partitioned = ORSet.empty(),
            queue = sharedQueue,
            results = ORMap.empty(),
        )
    }

    // Ground-truth membership — drives churn events and strong-membership agreement.
    var groundTruthJoins = initialJoins
    var groundTruthLeaves = ORSet.empty<String>()
    var groundTruthPartitioned = ORSet.empty<String>()

    // Strong-membership: the agreed ring advances after a consensus round per change.
    var agreedJoins = initialJoins
    var agreedLeaves = ORSet.empty<String>()
    var agreedPartitioned = ORSet.empty<String>()

    var totalMembershipCoordMessages = 0
    val claimsPerTask = mutableMapOf<TaskId, MutableList<ReplicaId>>()
    var deferredGossip: List<DDeferredMessage> = emptyList()
    var nextPeerIdx = peerCount

    repeat(rounds) {
        // 1. Apply deferred gossip from prior rounds.
        val (resolved, stillDeferred) = deferredGossip.partition { rng.nextDouble() >= gossipConfig.messageLossRate }
        deferredGossip = stillDeferred
        peers = applyDDeferred(peers, resolved)

        // 2. Churn events — update ground-truth ORSets.
        val churnResult = applyChurn(
            peers = peers,
            groundTruthJoins = groundTruthJoins,
            groundTruthLeaves = groundTruthLeaves,
            groundTruthPartitioned = groundTruthPartitioned,
            churnConfig = churnConfig,
            sharedQueue = sharedQueue,
            nextPeerIdx = nextPeerIdx,
            initialIds = initialIds,
            rng = rng,
        )
        peers = churnResult.peers
        groundTruthJoins = churnResult.newJoins
        groundTruthLeaves = churnResult.newLeaves
        groundTruthPartitioned = churnResult.newPartitioned
        nextPeerIdx = churnResult.nextPeerIdx

        // Inject membership deltas into ONE surviving witness peer so the delta has a source to
        // gossip from. Without this, a departing peer's leave-delta is gone when the peer is
        // removed — no surviving peer has it, and gossip can never propagate what no one holds.
        // Reality: one peer (or the coordinator) always witnesses each event directly.
        if (churnResult.joins + churnResult.leaves + churnResult.partitions > 0 && peers.isNotEmpty()) {
            val witnessIdx = 0  // Lowest-indexed surviving peer acts as the event witness.
            peers = peers.toMutableList().also { list ->
                val witness = list[witnessIdx]
                list[witnessIdx] = witness.copy(
                    memberJoins = witness.memberJoins.piece(groundTruthJoins),
                    memberLeaves = witness.memberLeaves.piece(groundTruthLeaves),
                    partitioned = witness.partitioned.piece(groundTruthPartitioned),
                )
            }
        }

        // Strong-membership consensus cost: charge per membership change, then advance agreed ring.
        val membershipChanges = churnResult.joins + churnResult.leaves + churnResult.partitions
        if (membershipChanges > 0) {
            val liveCount = (groundTruthJoins.elements - groundTruthLeaves.elements).size
            val quorumSize = (liveCount / 2) + 1
            totalMembershipCoordMessages += membershipChanges * 2 * quorumSize
            agreedJoins = groundTruthJoins
            agreedLeaves = groundTruthLeaves
            agreedPartitioned = groundTruthPartitioned
        }

        // 3. Hash-ring assignment: each peer uses its LOCAL membership view from BEFORE gossip.
        //    This is the disagreement window: churn happened (step 2), the witness has the new
        //    delta, but other peers haven't received it yet. Peers with stale rings may
        //    simultaneously claim the same task that the true owner (under the new ring) also claims.
        val groundTruthLeavesElements = groundTruthLeaves.elements
        val roundClaims = mutableListOf<Pair<TaskId, ReplicaId>>()
        peers = peers.map { peer ->
            // Ground-truth gate: skip peers that have truly left or are partitioned (they physically
            // can't execute). Their local view may still show themselves as alive — that's fine,
            // we just don't let them execute since they're actually gone.
            if (peer.id.value in groundTruthLeavesElements) return@map peer
            if (peer.id.value in groundTruthPartitioned.elements) return@map peer

            val effectiveMembers: Set<ReplicaId>
            val effectiveDown: Set<ReplicaId>
            if (strongMembership) {
                effectiveMembers = (agreedJoins.elements - agreedLeaves.elements).map { ReplicaId(it) }.toSet()
                effectiveDown = (agreedPartitioned.elements - agreedLeaves.elements).map { ReplicaId(it) }.toSet()
            } else {
                // Use the peer's LOCAL gossip view — possibly stale after a churn event.
                effectiveMembers = peer.effectiveMembers
                effectiveDown = peer.downPeers
            }

            val ring = HashRing(
                members = effectiveMembers - effectiveDown,
                vnodeCount = churnConfig.vnodeCount,
                downPeers = effectiveDown,
            )

            val newExecutions = peer.queue.elements.filter { taskId ->
                ring.owner(taskId) == peer.id && taskId !in peer.results.keys && taskId !in peer.executions
            }
            newExecutions.forEach { roundClaims.add(it to peer.id) }
            peer.copy(executions = peer.executions + newExecutions)
        }

        for ((taskId, claimedBy) in roundClaims) {
            claimsPerTask.getOrPut(taskId) { mutableListOf() }.add(claimedBy)
        }

        // 4. Execute: write results for claimed tasks.
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

        // 5a. Task replenishment — inject new tasks into all live peers' queues each round.
        //     Models a streaming workload: tasks continuously arrive. Without this, the initial
        //     batch gets claimed in round 1-2 and subsequent churn has no unclaimed tasks to race over.
        if (tasksPerRound > 0 && peers.isNotEmpty()) {
            val witness = peers[0]
            var addedQueue = witness.queue
            repeat(tasksPerRound) {
                addedQueue = addedQueue.add(witness.id, TaskId("task-$nextTaskIdx"))
                nextTaskIdx++
            }
            totalTaskCount += tasksPerRound
            peers = peers.map { p -> p.copy(queue = p.queue.piece(addedQueue)) }
        }

        // 5. Gossip propagation — membership deltas + tasks + results travel the same path.
        //    After this round, peers converge toward the new membership over [propagationHops] hops.
        val activeViews = buildActiveViews(peers.map { it.id }, gossipConfig.fanout, Random(rng.nextLong()))
        val (afterGossip, moreDeferred) = dGossipPhase(peers, activeViews, gossipConfig, rng)
        peers = afterGossip
        deferredGossip = deferredGossip + moreDeferred
    }

    // Final convergence: force-merge all live (non-left) peers.
    val leftIds = groundTruthLeaves.elements
    val livePeers = peers.filter { it.id.value !in leftIds }
    val convergedPeers = forceDConverge(livePeers)

    val tasksLost = assertDConvergence(convergedPeers, totalTaskCount, label)
    return buildRingResult(
        label = label,
        taskCount = totalTaskCount,
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
// Churn engine — updates ground-truth ORSets, injects changes into peers
// ---------------------------------------------------------------------------

private data class ChurnOutput(
    val peers: List<DPeerState>,
    val newJoins: ORSet<String>,
    val newLeaves: ORSet<String>,
    val newPartitioned: ORSet<String>,
    val nextPeerIdx: Int,
    val joins: Int,
    val leaves: Int,
    val partitions: Int,
)

private fun applyChurn(
    peers: List<DPeerState>,
    groundTruthJoins: ORSet<String>,
    groundTruthLeaves: ORSet<String>,
    groundTruthPartitioned: ORSet<String>,
    churnConfig: ChurnConfig,
    sharedQueue: ORSet<TaskId>,
    nextPeerIdx: Int,
    initialIds: List<ReplicaId>,
    rng: Random,
): ChurnOutput {
    val liveIds = (groundTruthJoins.elements - groundTruthLeaves.elements)
    val notPartitioned = liveIds - groundTruthPartitioned.elements
    var updatedPeers = peers.toMutableList()
    var newJoins = groundTruthJoins
    var newLeaves = groundTruthLeaves
    var newPartitioned = groundTruthPartitioned
    var joinCount = 0
    var leaveCount = 0
    var partitionCount = 0
    var nextIdx = nextPeerIdx

    // Partitions: some live, non-partitioned peers become unreachable.
    for (id in notPartitioned.toList()) {
        if (rng.nextInt(100) < churnConfig.partitionRatePercent) {
            // The partitioned peer adds itself to the partition set — but only THAT peer's
            // ORSet is updated locally; others learn via gossip. Model: peer adds to its own
            // partitioned set; this delta propagates through gossip in subsequent rounds.
            val peerIdx = updatedPeers.indexOfFirst { it.id.value == id }
            if (peerIdx >= 0) {
                val peer = updatedPeers[peerIdx]
                val newSet = newPartitioned.add(peer.id, id)
                newPartitioned = newSet
                // The partitioned peer knows it's partitioned immediately (self-detection).
                updatedPeers[peerIdx] = peer.copy(partitioned = newSet)
            }
            partitionCount++
        }
    }

    // Leaves: some non-partitioned live peers cleanly depart.
    for (id in notPartitioned.toList()) {
        if (id !in newPartitioned.elements && rng.nextInt(100) < churnConfig.leaveRatePercent) {
            val peerIdx = updatedPeers.indexOfFirst { it.id.value == id }
            if (peerIdx >= 0) {
                val peer = updatedPeers[peerIdx]
                // Departing peer writes its id to the leave ORSet and removes itself.
                // The delta will propagate to others via gossip.
                newLeaves = newLeaves.add(peer.id, id)
                updatedPeers.removeAt(peerIdx)
            }
            leaveCount++
        }
    }

    // Joins: new peers appear.
    if (churnConfig.joinRatePercent > 0 && rng.nextInt(100) < churnConfig.joinRatePercent) {
        val newId = ReplicaId("peer-$nextIdx")
        nextIdx++
        // Joiner adds itself to the join set — delta propagates to others via gossip.
        newJoins = newJoins.add(newId, newId.value)
        val newPeer = DPeerState(
            id = newId,
            memberJoins = newJoins,
            memberLeaves = newLeaves,
            partitioned = newPartitioned,
            queue = sharedQueue,
            results = ORMap.empty(),
        )
        updatedPeers.add(newPeer)
        joinCount++
    }

    return ChurnOutput(
        peers = updatedPeers,
        newJoins = newJoins,
        newLeaves = newLeaves,
        newPartitioned = newPartitioned,
        nextPeerIdx = nextIdx,
        joins = joinCount,
        leaves = leaveCount,
        partitions = partitionCount,
    )
}

// ---------------------------------------------------------------------------
// Gossip propagation — membership + task queue + results, same path
// ---------------------------------------------------------------------------

private data class DDeferredMessage(
    val targetIdx: Int,
    val memberJoins: ORSet<String>,
    val memberLeaves: ORSet<String>,
    val partitioned: ORSet<String>,
    val queue: ORSet<TaskId>,
    val results: ORMap<TaskId, LWWRegister<String>>,
)

/**
 * One gossip phase: each peer fans its full CRDT state (membership + tasks + results)
 * to its active-view neighbours over [gossipConfig.propagationHops] hops, subject to
 * per-hop [gossipConfig.partitionRate] drops and [gossipConfig.messageLossRate] deferrals.
 *
 * Membership deltas are carried in the SAME message as task/result deltas — no separate
 * channel, no instant delivery. This is the v4 fix: membership convergence now lags
 * identically to task/result convergence.
 */
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
                val message = DDeferredMessage(
                    targetIdx = dstIdx,
                    memberJoins = src.memberJoins,
                    memberLeaves = src.memberLeaves,
                    partitioned = src.partitioned,
                    queue = src.queue,
                    results = src.results,
                )
                if (rng.nextDouble() < gossipConfig.messageLossRate) {
                    deferred.add(message)
                } else {
                    val dst = mutablePeers[dstIdx]
                    mutablePeers[dstIdx] = dst.copy(
                        memberJoins = dst.memberJoins.piece(src.memberJoins),
                        memberLeaves = dst.memberLeaves.piece(src.memberLeaves),
                        partitioned = dst.partitioned.piece(src.partitioned),
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
            memberJoins = dst.memberJoins.piece(msg.memberJoins),
            memberLeaves = dst.memberLeaves.piece(msg.memberLeaves),
            partitioned = dst.partitioned.piece(msg.partitioned),
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
    val mergedJoins = peers.fold(ORSet.empty<String>()) { acc, p -> acc.piece(p.memberJoins) }
    val mergedLeaves = peers.fold(ORSet.empty<String>()) { acc, p -> acc.piece(p.memberLeaves) }
    val mergedPartitioned = peers.fold(ORSet.empty<String>()) { acc, p -> acc.piece(p.partitioned) }
    val mergedQueue = peers.fold(ORSet.empty<TaskId>()) { acc, p -> acc.piece(p.queue) }
    val mergedResults = peers.fold(ORMap.empty<TaskId, LWWRegister<String>>()) { acc, p -> acc.piece(p.results) }
    return peers.map { p ->
        p.copy(
            memberJoins = mergedJoins,
            memberLeaves = mergedLeaves,
            partitioned = mergedPartitioned,
            queue = mergedQueue,
            results = mergedResults,
        )
    }
}

/**
 * Asserts that all live peers converge to identical results and returns the count of
 * tasks that were never executed (all owners simultaneously down or departed).
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
// Result assembly — live-peer-only accounting
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
    // Filter to claims by peers still present at final convergence.
    // Departed peers' claims are phantom — exclude from both numerator and denominator.
    val livePeerIds = peers.map { it.id }.toSet()
    val liveClaimsPerTask = claimsPerTask
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
        gossipConfig = gossipConfig,
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
// v2 baseline integration
// ---------------------------------------------------------------------------

/**
 * Run Strategy D alongside the three v2 baselines (OPT/IR/CONS) at matched configs.
 */
internal fun runStrategyDWithBaseline(
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    churnConfig: ChurnConfig,
    gossipConfig: GossipConfig,
    rng: Random,
    tasksPerRound: Int = 0,
): DWithBaselineResult {
    val seed = rng.nextLong()
    val d = runStrategyD(taskCount, peerCount, rounds, churnConfig, gossipConfig, Random(seed), tasksPerRound)
    val v2 = runWarpComparisonV2(taskCount, peerCount, rounds, gossipConfig, Random(seed + 10))
    return DWithBaselineResult(d, v2)
}

internal data class DWithBaselineResult(
    val d: StrategyDResult,
    val v2: V2ComparisonResult,
)
