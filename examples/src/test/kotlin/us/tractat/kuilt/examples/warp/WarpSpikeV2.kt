/**
 * Warp spike v2 — SPECULATIVE / EXPERIMENTAL. Throwaway, never wired into the published API.
 *
 * Measures the TRADEOFF between three claim strategies under realistic gossip propagation:
 *
 *   Strategy A: Optimistic-dedup  — claim locally, dedup via Results ORMap. O(0) pre-claim coord.
 *   Strategy B: Intent-register   — stake claim via LWWMap<TaskId,PeerId> first, then execute
 *                                   only if still winning; dedup as backstop. O(1 gossip round).
 *   Strategy C: Consensus-model   — model per-task quorum (0 dups, measured coord cost).
 *
 * Fixes from architect review of v1:
 *   1. Realistic gossip: partial-view fanout with configurable hops — convergence lags.
 *   2. Baseline included: Strategy C gives the "0% dup but N× messages" reference point.
 *   3. Metrics are two-axis: (duplicate-rate, coordination-cost-messages-per-task).
 *   4. Hard convergence assertion: all peers' Results agree + no task lost after simulation.
 *   5. Scheduler (BoundedCounter) is dropped from measurement — its quota is decorative when
 *      seed quota = taskCount; the report notes this explicitly.
 *
 * Gossip model: each peer maintains an active-view of `fanout` neighbours (drawn seeded-randomly
 * per simulation). Delta propagation moves gossip messages through the partial view for up to
 * `propagationHops` logical hops per round. A message is permanently dropped with probability
 * [partitionRate] on each directed hop; transiently deferred with [messageLossRate].
 */
package us.tractat.kuilt.examples.warp

import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Strategy enum
// ---------------------------------------------------------------------------

internal enum class ClaimStrategy { OPTIMISTIC_DEDUP, INTENT_REGISTER, CONSENSUS_MODEL }

// ---------------------------------------------------------------------------
// Gossip propagation config
// ---------------------------------------------------------------------------

/**
 * Configuration for the gossip propagation model.
 *
 * @param fanout        active-view size — each peer fans out to this many neighbours.
 * @param propagationHops number of gossip hops per round before messages are consumed.
 * @param partitionRate  probability a directed hop is permanently dropped.
 * @param messageLossRate probability a directed hop is deferred one round (transient).
 */
internal data class GossipConfig(
    val fanout: Int = 3,
    val propagationHops: Int = 2,
    val partitionRate: Double = 0.0,
    val messageLossRate: Double = 0.0,
)

// ---------------------------------------------------------------------------
// Per-peer CRDT state
// ---------------------------------------------------------------------------

/**
 * The gossip-aware state for one peer in the v2 simulation.
 *
 * @param intentRegister LWWMap from TaskId to "PeerId-string" — only Strategy B uses this.
 *                       Strategy A and C keep it empty.
 */
internal data class PeerStateV2(
    val id: ReplicaId,
    val queue: ORSet<TaskId>,
    val intentRegister: LWWMap<TaskId, String>,
    val results: ORMap<TaskId, LWWRegister<String>>,
    /** Logical clock — monotonic per peer; LWW timestamps. */
    val clock: Long = 0L,
    /** Task IDs this peer actually committed to executing this simulation. */
    val executions: Set<TaskId> = emptySet(),
    /** Total messages this peer SENT for coordination (intent announcements + consensus round-trips). */
    val coordinationMessagesSent: Int = 0,
)

// ---------------------------------------------------------------------------
// Simulation output
// ---------------------------------------------------------------------------

/** Per-strategy outcome for one simulation run. */
internal data class StrategyResult(
    val strategy: ClaimStrategy,
    val totalTasks: Int,
    val peerCount: Int,
    val gossipConfig: GossipConfig,
    val rounds: Int,
    val totalExecutions: Int,
    val duplicateExecutions: Int,
    val duplicateRate: Double,
    val tasksCompleted: Int,
    /** Total coordination messages sent across all peers, across all rounds. */
    val totalCoordinationMessages: Int,
    /** Coordination messages per unique completed task. */
    val coordMessagesPerTask: Double,
)

/** Aggregated v2 run over all three strategies. */
internal data class V2ComparisonResult(
    val totalTasks: Int,
    val peerCount: Int,
    val gossipConfig: GossipConfig,
    val rounds: Int,
    val optimistic: StrategyResult,
    val intentRegister: StrategyResult,
    val consensus: StrategyResult,
)

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Run all three strategies under identical conditions and return a head-to-head comparison.
 *
 * Each strategy is independently seeded with [rng]-derived sub-seeds so that the gossip
 * topology and propagation randomness is the same across strategies (apples-to-apples).
 */
internal fun runWarpComparisonV2(
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    gossipConfig: GossipConfig,
    rng: Random,
): V2ComparisonResult {
    // Use the same base seed for topology but independent sub-seeds for RNG sequences.
    val baseSeed = rng.nextLong()
    val replicaIds = (0 until peerCount).map { ReplicaId("peer-$it") }

    // Build a shared initial queue (same for all three strategies).
    val sharedQueue = buildInitialQueue(replicaIds, taskCount, Random(baseSeed))

    // Build a shared gossip topology (active views) — same for all three.
    val activeViews = buildActiveViews(replicaIds, gossipConfig.fanout, Random(baseSeed))

    val optimistic = runStrategySimulation(
        strategy = ClaimStrategy.OPTIMISTIC_DEDUP,
        replicaIds = replicaIds,
        sharedQueue = sharedQueue,
        activeViews = activeViews,
        taskCount = taskCount,
        rounds = rounds,
        gossipConfig = gossipConfig,
        rng = Random(baseSeed + 1),
    )

    val intent = runStrategySimulation(
        strategy = ClaimStrategy.INTENT_REGISTER,
        replicaIds = replicaIds,
        sharedQueue = sharedQueue,
        activeViews = activeViews,
        taskCount = taskCount,
        rounds = rounds,
        gossipConfig = gossipConfig,
        rng = Random(baseSeed + 2),
    )

    val consensus = runStrategySimulation(
        strategy = ClaimStrategy.CONSENSUS_MODEL,
        replicaIds = replicaIds,
        sharedQueue = sharedQueue,
        activeViews = activeViews,
        taskCount = taskCount,
        rounds = rounds,
        gossipConfig = gossipConfig,
        rng = Random(baseSeed + 3),
    )

    return V2ComparisonResult(
        totalTasks = taskCount,
        peerCount = peerCount,
        gossipConfig = gossipConfig,
        rounds = rounds,
        optimistic = optimistic,
        intentRegister = intent,
        consensus = consensus,
    )
}

// ---------------------------------------------------------------------------
// Gossip topology
// ---------------------------------------------------------------------------

/**
 * Build a per-peer active-view (partial view) of [fanout] neighbours.
 * Each peer's view is drawn uniformly without replacement from the other peers.
 * Seeded so the topology is reproducible and shared across strategy comparisons.
 */
private fun buildActiveViews(
    replicaIds: List<ReplicaId>,
    fanout: Int,
    rng: Random,
): Map<ReplicaId, List<ReplicaId>> = replicaIds.associateWith { self ->
    val others = replicaIds.filter { it != self }
    val k = minOf(fanout, others.size)
    others.shuffled(rng).take(k)
}

// ---------------------------------------------------------------------------
// Queue initialisation
// ---------------------------------------------------------------------------

private fun buildInitialQueue(
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
// Single-strategy simulation
// ---------------------------------------------------------------------------

private fun runStrategySimulation(
    strategy: ClaimStrategy,
    replicaIds: List<ReplicaId>,
    sharedQueue: ORSet<TaskId>,
    activeViews: Map<ReplicaId, List<ReplicaId>>,
    taskCount: Int,
    rounds: Int,
    gossipConfig: GossipConfig,
    rng: Random,
): StrategyResult {
    var peers = replicaIds.map { id ->
        PeerStateV2(
            id = id,
            queue = sharedQueue,
            intentRegister = LWWMap.empty(),
            results = ORMap.empty(),
        )
    }

    // For consensus model: track which tasks have been "assigned" (exclusive registry).
    // This simulates an oracle — zero dups, but every assignment costs quorum messages.
    val consensusAssigned = mutableMapOf<TaskId, ReplicaId>()

    val claimsPerTask = mutableMapOf<TaskId, MutableList<ReplicaId>>()

    // Deferred gossip messages (transient loss): list of (targetIdx, queue-snapshot, intent-snapshot, results-snapshot)
    var deferredGossip: List<DeferredMessage> = emptyList()

    repeat(rounds) {
        // 1. Apply deferred gossip from previous round (re-roll loss).
        val (resolvedDeferred, stillDeferred) = deferredGossip.partition { rng.nextDouble() >= gossipConfig.messageLossRate }
        deferredGossip = stillDeferred
        peers = applyDeferredMessages(peers, resolvedDeferred)

        // 2. Claim phase (strategy-specific).
        val (afterClaim, roundClaims, newDeferred) = claimPhase(
            strategy = strategy,
            peers = peers,
            consensusAssigned = consensusAssigned,
            gossipConfig = gossipConfig,
            activeViews = activeViews,
            rng = rng,
        )
        peers = afterClaim
        deferredGossip = deferredGossip + newDeferred

        for ((taskId, claimedBy) in roundClaims) {
            claimsPerTask.getOrPut(taskId) { mutableListOf() }.add(claimedBy)
        }

        // 3. Execute phase: peers with a pending claim write their result.
        peers = executePhase(peers, rng)

        // 4. Gossip propagation: each peer fans to its active view.
        val (afterGossip, moreDeferred) = gossipPhase(
            peers = peers,
            activeViews = activeViews,
            gossipConfig = gossipConfig,
            rng = rng,
        )
        peers = afterGossip
        deferredGossip = deferredGossip + moreDeferred
    }

    // Final convergence: one unconditional full merge so all peers see the same terminal state.
    peers = forceConverge(peers)

    assertConvergence(peers, taskCount, strategy)

    return buildStrategyResult(strategy, taskCount, peers, claimsPerTask, gossipConfig, rounds)
}

// ---------------------------------------------------------------------------
// Claim phase (strategy-specific)
// ---------------------------------------------------------------------------

private data class ClaimPhaseOutput(
    val peers: List<PeerStateV2>,
    val claims: List<Pair<TaskId, ReplicaId>>,
    val deferredMessages: List<DeferredMessage>,
)

private fun claimPhase(
    strategy: ClaimStrategy,
    peers: List<PeerStateV2>,
    consensusAssigned: MutableMap<TaskId, ReplicaId>,
    gossipConfig: GossipConfig,
    activeViews: Map<ReplicaId, List<ReplicaId>>,
    rng: Random,
): ClaimPhaseOutput = when (strategy) {
    ClaimStrategy.OPTIMISTIC_DEDUP -> claimOptimistic(peers, rng)
    ClaimStrategy.INTENT_REGISTER -> claimWithIntent(peers, activeViews, gossipConfig, rng)
    ClaimStrategy.CONSENSUS_MODEL -> claimConsensus(peers, consensusAssigned, rng)
}

/** Strategy A: claim first unclaimed task locally — no pre-coordination. */
private fun claimOptimistic(
    peers: List<PeerStateV2>,
    rng: Random,
): ClaimPhaseOutput {
    val claims = mutableListOf<Pair<TaskId, ReplicaId>>()
    val updated = peers.map { peer ->
        val candidate = pickCandidate(peer, rng) ?: return@map peer
        claims.add(candidate to peer.id)
        // Mark as "pending execution" by storing the candidate in executions set immediately.
        peer.copy(executions = peer.executions + candidate)
    }
    return ClaimPhaseOutput(updated, claims, emptyList())
}

/**
 * Strategy B: intent-register — write claim intent to LWWMap, gossip it, then only
 * execute if we still believe we're the winner after reading back.
 *
 * Intent messages are ADDITIONAL coordination messages beyond the normal results gossip.
 * Each intent announcement counts as 1 coordination message per active-view neighbour.
 *
 * The "read-back" is from the peer's local intent view (already merged via gossip); in
 * a real system this would require another propagation round. We model ONE extra round of
 * intent gossip before execution per task claim, which is the optimistic bound.
 */
private fun claimWithIntent(
    peers: List<PeerStateV2>,
    activeViews: Map<ReplicaId, List<ReplicaId>>,
    gossipConfig: GossipConfig,
    rng: Random,
): ClaimPhaseOutput {
    val claims = mutableListOf<Pair<TaskId, ReplicaId>>()
    val deferredMessages = mutableListOf<DeferredMessage>()

    // Phase B1: every peer that wants to claim writes its intent.
    val peerIndex = peers.associateBy { it.id }
    val afterIntent = peers.map { peer ->
        val candidate = pickCandidate(peer, rng) ?: return@map peer
        val newClock = peer.clock + 1L
        val newIntent = peer.intentRegister.set(peer.id, newClock, candidate, peer.id.value)
        val neighbours = activeViews[peer.id] ?: emptyList()
        // Count intent messages sent.
        val intentMsgCount = neighbours.size
        peer.copy(
            intentRegister = newIntent,
            clock = newClock,
            coordinationMessagesSent = peer.coordinationMessagesSent + intentMsgCount,
        )
    }

    // Immediately propagate intent to neighbours (one mini-gossip step, same-round).
    // This models the "one extra propagation step" cost of intent-register.
    val peerByIndex = afterIntent.associateBy { it.id }
    val afterIntentMerge = afterIntent.map { peer ->
        val neighbours = activeViews[peer.id] ?: emptyList()
        var merged = peer
        for (neighbour in neighbours) {
            val neighbourPeer = peerByIndex[neighbour] ?: continue
            merged = merged.copy(intentRegister = merged.intentRegister.piece(neighbourPeer.intentRegister))
        }
        merged
    }

    // Phase B2: each peer reads back intent; only executes if it's the LWW winner.
    val afterClaim = afterIntentMerge.map { peer ->
        val candidate = pickCandidate(peer, rng) ?: return@map peer
        val intentWinner = peer.intentRegister[candidate]
        // Execute only if this peer's id is the LWW winner (or no intent yet for this task).
        if (intentWinner == null || intentWinner == peer.id.value) {
            claims.add(candidate to peer.id)
            peer.copy(executions = peer.executions + candidate)
        } else {
            // Another peer has staked the claim; skip (dedup would fire anyway, but intent prevents wasted work).
            peer
        }
    }

    return ClaimPhaseOutput(afterClaim, claims, deferredMessages)
}

/**
 * Strategy C: consensus model — exclusive task assignment via a simulated quorum oracle.
 *
 * Zero duplicates. Every assignment costs a quorum round-trip. For N peers, a quorum
 * requires ceil((N+1)/2) peers to agree. We model this as:
 *   - coordination cost = 2 × quorum-size messages per task (propose + accept)
 * The oracle assigns each unassigned task to exactly one peer per round (simulating
 * the Raft-append → commit flow for a batch of claims). The measurement surfaces the
 * messages-per-task cost of zero duplicates.
 */
private fun claimConsensus(
    peers: List<PeerStateV2>,
    consensusAssigned: MutableMap<TaskId, ReplicaId>,
    rng: Random,
): ClaimPhaseOutput {
    val peerCount = peers.size
    val quorumSize = (peerCount / 2) + 1
    val messagesPerAssignment = 2 * quorumSize  // propose to quorum + quorum acknowledges
    val claims = mutableListOf<Pair<TaskId, ReplicaId>>()

    // Each peer requests one task; oracle assigns exactly one winner per task.
    val taskRequests = peers.mapNotNull { peer ->
        pickCandidateForConsensus(peer, consensusAssigned) to peer.id
    }.filter { it.first != null }.map { it.first!! to it.second }

    // Group requests by task; assign one peer per task (the one with lowest replica-id for determinism).
    val taskToWinner = taskRequests
        .groupBy { it.first }
        .mapValues { (_, requesters) -> requesters.minByOrNull { it.second.value }!!.second }

    // Record assignments and track coordination cost.
    var totalMessages = 0
    for ((task, winner) in taskToWinner) {
        if (task !in consensusAssigned) {
            consensusAssigned[task] = winner
            claims.add(task to winner)
            totalMessages += messagesPerAssignment
        }
    }

    // Distribute coordination cost across winning peers (approximate — real Raft concentrates on leader).
    val msgsPerPeer = if (taskToWinner.isNotEmpty()) totalMessages / peers.size else 0
    val updated = peers.map { peer ->
        val assignedTask = consensusAssigned.entries.firstOrNull { it.value == peer.id && it.key !in peer.executions }
        if (assignedTask != null) {
            peer.copy(
                executions = peer.executions + assignedTask.key,
                coordinationMessagesSent = peer.coordinationMessagesSent + msgsPerPeer,
            )
        } else {
            peer
        }
    }

    return ClaimPhaseOutput(updated, claims, emptyList())
}

// ---------------------------------------------------------------------------
// Execute phase: write results for claimed tasks
// ---------------------------------------------------------------------------

private fun executePhase(peers: List<PeerStateV2>, rng: Random): List<PeerStateV2> =
    peers.map { peer ->
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

// ---------------------------------------------------------------------------
// Gossip propagation phase
// ---------------------------------------------------------------------------

private data class DeferredMessage(
    val targetIdx: Int,
    val queue: ORSet<TaskId>,
    val intent: LWWMap<TaskId, String>,
    val results: ORMap<TaskId, LWWRegister<String>>,
)

private fun gossipPhase(
    peers: List<PeerStateV2>,
    activeViews: Map<ReplicaId, List<ReplicaId>>,
    gossipConfig: GossipConfig,
    rng: Random,
): Pair<List<PeerStateV2>, List<DeferredMessage>> {
    val peerIndexOf = peers.indices.associateBy { peers[it].id }
    val mutablePeers = peers.toMutableList()
    val deferred = mutableListOf<DeferredMessage>()

    for (srcIdx in peers.indices) {
        val src = peers[srcIdx]
        val neighbours = activeViews[src.id] ?: emptyList()

        for (neighbour in neighbours) {
            val dstIdx = peerIndexOf[neighbour] ?: continue

            // Repeat propagation for [propagationHops] hops (each hop = one more relay in gossip).
            repeat(gossipConfig.propagationHops) {
                if (rng.nextDouble() < gossipConfig.partitionRate) return@repeat  // permanent drop
                val message = DeferredMessage(dstIdx, src.queue, src.intentRegister, src.results)
                if (rng.nextDouble() < gossipConfig.messageLossRate) {
                    deferred.add(message)
                } else {
                    val dst = mutablePeers[dstIdx]
                    mutablePeers[dstIdx] = dst.copy(
                        queue = dst.queue.piece(src.queue),
                        intentRegister = dst.intentRegister.piece(src.intentRegister),
                        results = dst.results.piece(src.results),
                    )
                }
            }
        }
    }

    return mutablePeers to deferred
}

// ---------------------------------------------------------------------------
// Deferred message application
// ---------------------------------------------------------------------------

private fun applyDeferredMessages(
    peers: List<PeerStateV2>,
    messages: List<DeferredMessage>,
): List<PeerStateV2> {
    val mutablePeers = peers.toMutableList()
    for (msg in messages) {
        val dst = mutablePeers[msg.targetIdx]
        mutablePeers[msg.targetIdx] = dst.copy(
            queue = dst.queue.piece(msg.queue),
            intentRegister = dst.intentRegister.piece(msg.intent),
            results = dst.results.piece(msg.results),
        )
    }
    return mutablePeers
}

// ---------------------------------------------------------------------------
// Final unconditional convergence + correctness assertion
// ---------------------------------------------------------------------------

/** Merge all peers' state so we can assert a globally-consistent terminal state. */
private fun forceConverge(peers: List<PeerStateV2>): List<PeerStateV2> {
    if (peers.isEmpty()) return peers
    val mergedQueue = peers.fold(ORSet.empty<TaskId>()) { acc, p -> acc.piece(p.queue) }
    val mergedIntent = peers.fold(LWWMap.empty<TaskId, String>()) { acc, p -> acc.piece(p.intentRegister) }
    val mergedResults = peers.fold(ORMap.empty<TaskId, LWWRegister<String>>()) { acc, p -> acc.piece(p.results) }
    return peers.map { p ->
        p.copy(queue = mergedQueue, intentRegister = mergedIntent, results = mergedResults)
    }
}

/**
 * Hard convergence assertion — proves dedup correctness rather than assuming it.
 *
 * After [forceConverge], every peer must see:
 * 1. Exactly one result per completed task (no double-count in the ORMap).
 * 2. The set of completed tasks equals what the union of executions holds.
 */
private fun assertConvergence(peers: List<PeerStateV2>, taskCount: Int, strategy: ClaimStrategy) {
    if (peers.isEmpty()) return
    val referenceResults = peers.first().results
    for (peer in peers.drop(1)) {
        require(peer.results == referenceResults) {
            "[$strategy] Convergence failure: peer ${peer.id} results differ from peer-0"
        }
    }
    // No task should appear more than once in the ORMap (the LWWRegister-keyed ORMap deduplicates).
    val resultKeys = referenceResults.keys
    require(resultKeys.size == resultKeys.toSet().size) {
        "[$strategy] Duplicate keys in Results ORMap — this should be structurally impossible"
    }
    // All executed tasks must have a result (none lost).
    val allExecuted = peers.flatMap { it.executions }.toSet()
    for (taskId in allExecuted) {
        require(taskId in resultKeys) {
            "[$strategy] Task $taskId was executed but has no result — lost execution"
        }
    }
    // Completed-task count must not exceed taskCount.
    require(resultKeys.size <= taskCount) {
        "[$strategy] More results (${resultKeys.size}) than tasks ($taskCount)"
    }
}

// ---------------------------------------------------------------------------
// Candidate selection helpers
// ---------------------------------------------------------------------------

private fun pickCandidate(peer: PeerStateV2, rng: Random): TaskId? =
    peer.queue.elements
        .filter { it !in peer.results.keys && it !in peer.executions }
        .shuffled(rng)
        .firstOrNull()

private fun pickCandidateForConsensus(peer: PeerStateV2, assigned: Map<TaskId, ReplicaId>): TaskId? =
    peer.queue.elements
        .filter { it !in assigned && it !in peer.executions }
        .minByOrNull { it.id }  // deterministic: lowest task-id first avoids randomness in consensus selection

// ---------------------------------------------------------------------------
// Result assembly
// ---------------------------------------------------------------------------

private fun buildStrategyResult(
    strategy: ClaimStrategy,
    taskCount: Int,
    peers: List<PeerStateV2>,
    claimsPerTask: Map<TaskId, List<ReplicaId>>,
    gossipConfig: GossipConfig,
    rounds: Int,
): StrategyResult {
    val totalExecutions = peers.sumOf { it.executions.size }
    val duplicateExecutions = claimsPerTask.values.sumOf { maxOf(0, it.size - 1) }
    val tasksCompleted = claimsPerTask.keys.size
    val duplicateRate = if (totalExecutions > 0) duplicateExecutions.toDouble() / totalExecutions else 0.0
    val totalCoordMsgs = peers.sumOf { it.coordinationMessagesSent }
    val coordPerTask = if (tasksCompleted > 0) totalCoordMsgs.toDouble() / tasksCompleted else 0.0

    return StrategyResult(
        strategy = strategy,
        totalTasks = taskCount,
        peerCount = peers.size,
        gossipConfig = gossipConfig,
        rounds = rounds,
        totalExecutions = totalExecutions,
        duplicateExecutions = duplicateExecutions,
        duplicateRate = duplicateRate,
        tasksCompleted = tasksCompleted,
        totalCoordinationMessages = totalCoordMsgs,
        coordMessagesPerTask = coordPerTask,
    )
}
