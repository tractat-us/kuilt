/**
 * Warp spike #680 — SPECULATIVE / EXPERIMENTAL. Throwaway, never wired into the published API.
 *
 * Models the exclusive-claim gap in a coordination-free CRDT work queue:
 *
 *   TaskQueue  = ORSet<TaskId>         — idempotent task distribution
 *   Results    = ORMap<TaskId, LWWRegister<String>> — dedup surface (task-id keyed)
 *   TaskScheduler = BoundedCounter     — queue-depth equalizer (diffusive load balance)
 *
 * Peers exchange CRDT deltas with configurable latency, loss, and partition.
 * Exchange is modeled as CRDT merge operations — no real coroutines or network.
 *
 * The measurement: duplicate-execution rate = tasks claimed by >1 peer before
 * convergence, dropped because the Results ORMap deduplicates on task id.
 */
package us.tractat.kuilt.examples.warp

import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Domain types
// ---------------------------------------------------------------------------

/** Identifies a unit of work. */
internal data class TaskId(val id: String)

/** A peer's view of the warp state. */
internal data class PeerState(
    val id: ReplicaId,
    val queue: ORSet<TaskId>,
    val results: ORMap<TaskId, LWWRegister<String>>,
    val scheduler: BoundedCounter,
    /** Logical clock — incremented on every result write; used as LWWRegister timestamp. */
    val clock: Long = 0L,
    /** Tasks this peer has claimed and executed during the simulation. */
    val executions: Set<TaskId> = emptySet(),
)

/** Outcome of one simulation run. */
internal data class SimulationResult(
    val totalTasks: Int,
    val peerCount: Int,
    val partitionRate: Double,
    val messageLossRate: Double,
    val rounds: Int,
    val totalExecutions: Int,
    val duplicateExecutions: Int,
    val duplicateRate: Double,
    val tasksCompleted: Int,
)

// ---------------------------------------------------------------------------
// Simulation
// ---------------------------------------------------------------------------

/**
 * Run one warp simulation:
 *
 * - [taskCount]      total tasks enqueued at the start (distributed across peers).
 * - [peerCount]      number of peers in the cluster.
 * - [rounds]         simulation steps (each round: every peer claims + executes + propagates).
 * - [partitionRate]  probability [0,1] that a given directed message is partitioned (dropped permanently).
 * - [messageLossRate] probability [0,1] that a delta merge is delayed by one round (transient loss).
 * - [rng]            seeded RNG for reproducibility.
 */
internal fun runWarpSimulation(
    taskCount: Int,
    peerCount: Int,
    rounds: Int,
    partitionRate: Double,
    messageLossRate: Double,
    rng: Random,
): SimulationResult {
    val replicaIds = (0 until peerCount).map { ReplicaId("peer-$it") }
    val peers = initPeers(replicaIds, taskCount, rng)

    // Track global execution: taskId -> list of peers that claimed it
    val claimsPerTask = mutableMapOf<TaskId, MutableList<ReplicaId>>()

    // Deferred merges from transient loss — applied next round
    var pendingMerges: List<Pair<Int, Pair<ORSet<TaskId>, ORMap<TaskId, LWWRegister<String>>>>> = emptyList()

    var currentPeers = peers

    repeat(rounds) {
        // Apply deferred merges from previous round
        val (resolved, deferred) = pendingMerges.partition { rng.nextDouble() >= messageLossRate }
        pendingMerges = deferred
        currentPeers = applyPendingMerges(currentPeers, resolved)

        // Each peer: claim tasks from local queue view, execute, record result
        val (updatedPeers, roundClaims) = executeRound(currentPeers, rng)
        currentPeers = updatedPeers

        // Record claims for duplicate analysis
        for ((taskId, claimedBy) in roundClaims) {
            claimsPerTask.getOrPut(taskId) { mutableListOf() }.add(claimedBy)
        }

        // Propagate deltas between peers (with loss and partition)
        val (propagated, newDeferred) = propagateDeltas(
            peers = currentPeers,
            rng = rng,
            partitionRate = partitionRate,
            messageLossRate = messageLossRate,
        )
        currentPeers = propagated
        pendingMerges = pendingMerges + newDeferred
    }

    return buildResult(
        taskCount = taskCount,
        peerCount = peerCount,
        partitionRate = partitionRate,
        messageLossRate = messageLossRate,
        rounds = rounds,
        peers = currentPeers,
        claimsPerTask = claimsPerTask,
    )
}

// ---------------------------------------------------------------------------
// Initialisation
// ---------------------------------------------------------------------------

private fun initPeers(
    replicaIds: List<ReplicaId>,
    taskCount: Int,
    rng: Random,
): List<PeerState> {
    val peerCount = replicaIds.size
    val seedQuota = taskCount.toLong()
    val quotas = replicaIds.associateWith { seedQuota }
    val sharedScheduler = BoundedCounter.init(quotas)

    // Distribute tasks — each task is added by a random peer so the initial ORSet
    // is non-trivially replicated right from the start.
    val tasks = (0 until taskCount).map { TaskId("task-$it") }
    var sharedQueue = ORSet.empty<TaskId>()
    for (task in tasks) {
        val owner = replicaIds[rng.nextInt(peerCount)]
        sharedQueue = sharedQueue.add(owner, task)
    }

    return replicaIds.map { id ->
        PeerState(
            id = id,
            queue = sharedQueue,
            results = ORMap.empty(),
            scheduler = sharedScheduler,
        )
    }
}

// ---------------------------------------------------------------------------
// Round execution
// ---------------------------------------------------------------------------

/**
 * Each peer claims one available task (the first unclaimed in its local queue view
 * that it has quota to execute). Returns updated peers + a log of (taskId, peerId)
 * claims made this round.
 */
private fun executeRound(
    peers: List<PeerState>,
    rng: Random,
): Pair<List<PeerState>, List<Pair<TaskId, ReplicaId>>> {
    val claims = mutableListOf<Pair<TaskId, ReplicaId>>()
    val updated = peers.map { peer ->
        val candidate = peer.queue.elements
            .filter { it !in peer.results.keys }
            .shuffled(rng)
            .firstOrNull { peer.scheduler.quota(peer.id) > 0L } ?: return@map peer

        // Claim: spend 1 quota unit, record execution
        val spendPatch = peer.scheduler.trySpend(peer.id) ?: return@map peer
        val newScheduler = peer.scheduler.piece(spendPatch.delta)

        // Execute: write result into ORMap (LWWRegister per task)
        val newClock = peer.clock + 1L
        val register = LWWRegister.empty<String>().set(peer.id, newClock, "result-by-${peer.id.value}")
        val newResults = peer.results.put(peer.id, candidate, register)

        claims.add(candidate to peer.id)
        peer.copy(
            results = newResults,
            scheduler = newScheduler,
            clock = newClock,
            executions = peer.executions + candidate,
        )
    }
    return updated to claims
}

// ---------------------------------------------------------------------------
// Delta propagation
// ---------------------------------------------------------------------------

/**
 * After each round, every peer broadcasts its current queue+results state to all
 * others. Each directed message is subject to:
 * - [partitionRate]: dropped permanently (simulates a network partition).
 * - [messageLossRate]: deferred one round (transient loss).
 *
 * Returns the new peer list + a list of deferred merges for next round.
 */
private fun propagateDeltas(
    peers: List<PeerState>,
    rng: Random,
    partitionRate: Double,
    messageLossRate: Double,
): Pair<List<PeerState>, List<Pair<Int, Pair<ORSet<TaskId>, ORMap<TaskId, LWWRegister<String>>>>>> {
    // Build: for each target peer, collect all incoming merges
    val incoming = Array(peers.size) {
        peers[it].queue to peers[it].results
    }
    val deferred = mutableListOf<Pair<Int, Pair<ORSet<TaskId>, ORMap<TaskId, LWWRegister<String>>>>>()

    val merged = incoming.toMutableList()
    for (srcIdx in peers.indices) {
        for (dstIdx in peers.indices) {
            if (srcIdx == dstIdx) continue
            if (rng.nextDouble() < partitionRate) continue  // permanent drop
            val payload = peers[srcIdx].queue to peers[srcIdx].results
            if (rng.nextDouble() < messageLossRate) {
                deferred.add(dstIdx to payload)
                continue
            }
            val (q, r) = merged[dstIdx]
            merged[dstIdx] = q.piece(payload.first) to r.piece(payload.second)
        }
    }

    val updatedPeers = peers.mapIndexed { idx, peer ->
        val (newQ, newR) = merged[idx]
        peer.copy(queue = newQ, results = newR)
    }
    return updatedPeers to deferred
}

private fun applyPendingMerges(
    peers: List<PeerState>,
    pending: List<Pair<Int, Pair<ORSet<TaskId>, ORMap<TaskId, LWWRegister<String>>>>>,
): List<PeerState> {
    val mutablePeers = peers.toMutableList()
    for ((dstIdx, payload) in pending) {
        val peer = mutablePeers[dstIdx]
        mutablePeers[dstIdx] = peer.copy(
            queue = peer.queue.piece(payload.first),
            results = peer.results.piece(payload.second),
        )
    }
    return mutablePeers
}

// ---------------------------------------------------------------------------
// Result assembly
// ---------------------------------------------------------------------------

private fun buildResult(
    taskCount: Int,
    peerCount: Int,
    partitionRate: Double,
    messageLossRate: Double,
    rounds: Int,
    peers: List<PeerState>,
    claimsPerTask: Map<TaskId, List<ReplicaId>>,
): SimulationResult {
    val totalExecutions = peers.sumOf { it.executions.size }
    val duplicateExecutions = claimsPerTask.values.sumOf { maxOf(0, it.size - 1) }
    val tasksCompleted = claimsPerTask.keys.size  // tasks claimed at least once
    val duplicateRate = if (totalExecutions > 0) duplicateExecutions.toDouble() / totalExecutions else 0.0

    return SimulationResult(
        totalTasks = taskCount,
        peerCount = peerCount,
        partitionRate = partitionRate,
        messageLossRate = messageLossRate,
        rounds = rounds,
        totalExecutions = totalExecutions,
        duplicateExecutions = duplicateExecutions,
        duplicateRate = duplicateRate,
        tasksCompleted = tasksCompleted,
    )
}
