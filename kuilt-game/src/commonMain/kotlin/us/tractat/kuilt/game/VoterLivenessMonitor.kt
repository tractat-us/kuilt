package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.time.Instant

/**
 * Watches over the live voter set for a hosted game and keeps it whole.
 *
 * When a voter drops off the network — a lost connection, or a deliberate "I'm leaving" — this
 * monitor notices, removes that seat from the game's membership, re-opens the door for one
 * replacement, and starts watching the newcomer in turn. It is created and started once, on the
 * host's session scope, by [gameHost] when the caller opts into liveness monitoring; cancelling
 * that scope tears the whole monitor down with it.
 *
 * ---
 *
 * For each currently-admitted voter (excluding self), [start] launches a
 * [HeartbeatPartitionDetector]. All detectors share the [HEARTBEAT_CHANNEL] seam for send (pings),
 * but each subscribes to a per-peer filtered view ([GamePerPeerSeam]) of a single shared
 * [MutableSharedFlow], satisfying the ADR-034 single-collection contract.
 *
 * On [PartitionEvent.PeerLost] (leader only): evicts the dead voter via [changeMembershipWithRetry],
 * re-opens admission, runs [admitVotersUntil] for one replacement, then starts a fresh detector for
 * the new voter. Graceful leave ([GamePresence.vacaters]) triggers eviction immediately without
 * waiting the reconnect window.
 *
 * Non-leader nodes receive [PartitionEvent.PeerLost] but take no action — Raft's commit-majority
 * gate means only the leader can commit the membership change.
 *
 * All coroutines launch on the injected [scope]; cancelling it tears them all down (the #1001-class
 * leak guard).
 *
 * @param scope The session scope that owns every coroutine this monitor launches.
 * @param node The leader [RaftNode] (may or may not currently hold the leader role).
 * @param seam The game seam (used for [PeerId] extraction and [nextVoterPeer]).
 * @param mux The [MuxSeam] wrapping [seam]; the liveness channel ([HEARTBEAT_CHANNEL]) is a view on it.
 * @param voters The mutable live voter set (shared with the admission loop; mutations are serialised
 *   by the single background eviction coroutine).
 * @param spectatorIds The mutable spectator NodeId set (passed to [ClusterConfig] to preserve learners).
 * @param peerCount Total configured voter count; used to re-open admission to exactly one replacement.
 * @param presence The [GamePresence] instance; used to detect vacaters and re-open/re-close admission.
 * @param config [HeartbeatConfig] driving ping interval, timeout, and reconnect window.
 * @param clock Clock for liveness measurements; injected for virtual-time test determinism.
 */
internal class VoterLivenessMonitor(
    private val scope: CoroutineScope,
    private val node: RaftNode,
    private val seam: Seam,
    private val mux: MuxSeam,
    private val voters: MutableSet<NodeId>,
    private val spectatorIds: MutableSet<NodeId>,
    private val peerCount: Int,
    private val presence: GamePresence,
    private val config: HeartbeatConfig,
    private val clock: () -> Instant,
) {
    private val self = NodeId(seam.selfId.value)
    private val heartbeatSeam: Seam = mux.channel(HEARTBEAT_CHANNEL)

    // Fan the liveness channel's incoming stream into a shared flow so multiple per-peer
    // [GamePerPeerSeam] instances can each subscribe independently — satisfying single-collection.
    private val rawLiveness = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)

    // Serialised evictions: detector coroutines send lost NodeIds here; the eviction loop
    // processes them one at a time on this scope. Channel.UNLIMITED so detector jobs never block.
    private val evictions = Channel<NodeId>(Channel.UNLIMITED)

    // Active detector job per voter; updated as voters leave and replacements join.
    private val detectorJobs = mutableMapOf<NodeId, Job>()

    /**
     * Starts liveness monitoring: fans the heartbeat channel, launches one detector per initial
     * voter (excluding self), and launches the graceful-leave watcher and the eviction loop. All
     * launches are children of [scope], so cancelling it tears everything down.
     */
    fun start() {
        launchLivenessFan()

        // Start one detector per initial admitted voter (excluding self).
        voters.filter { it != self }.forEach { voterId ->
            detectorJobs[voterId] = launchDetectorFor(voterId)
        }

        // Graceful-leave watcher: vacate signals bypass the reconnect window.
        scope.launch { watchVacaters() }

        // Eviction loop: process one PeerLost at a time.
        scope.launch { evictAndReopenAdmission() }
    }

    private fun launchLivenessFan() {
        scope.launch { heartbeatSeam.incoming.collect { rawLiveness.emit(it) } }
    }

    /**
     * Launches a [HeartbeatPartitionDetector] for [voterId] and returns its [Job].
     *
     * Delegates to the [CoroutineScope.launchDetectorFor] free function on [scope] so the returned
     * job owns *all* of the detector's coroutines (its heartbeat loop and inbound collector), not
     * just the events collector — cancelling the job tears the whole detector down (#1001-class
     * leak guard).
     */
    private fun launchDetectorFor(voterId: NodeId): Job =
        scope.launchDetectorFor(voterId, heartbeatSeam, rawLiveness, evictions, config, clock)

    /**
     * Watches for graceful-leave vacate signals on [presence] and triggers immediate eviction.
     *
     * Polls [GamePresence.vacaters] on every Quilter state change (announced flow). When a new
     * vacater is seen that is a current voter, sends its [NodeId] to [evictions] to bypass the
     * reconnect window.
     */
    private suspend fun watchVacaters() {
        val seenVacaters = mutableSetOf<NodeId>()
        presence.announced.collect {
            val newVacaters = presence.vacaters()
                .map { NodeId(it.value) }
                .filter { it in voters && it !in seenVacaters && it != self }
            newVacaters.forEach { vacaterId ->
                seenVacaters += vacaterId
                // Cancel the detector job for this voter — it's leaving voluntarily.
                detectorJobs.remove(vacaterId)?.cancel()
                evictions.trySend(vacaterId)
            }
        }
    }

    /**
     * Processes evictions from [evictions]: removes the dead voter, re-opens admission for one
     * replacement, then starts a fresh detector for the replacement.
     *
     * Only the Raft **leader** calls [changeMembershipWithRetry]; non-leaders skip the eviction.
     * If leadership has transferred by the time eviction fires, the new leader's own loop handles
     * the eviction — or the evicted peer's seat remains open until another PeerLost fires.
     */
    private suspend fun evictAndReopenAdmission() {
        // Peers that have been evicted from the voter set — excluded from re-admission so a gracefully
        // departing peer (still connected) is not immediately re-admitted to its own freed seat.
        val evictedVoterIds = mutableSetOf<NodeId>()

        for (lostId in evictions) {
            // Skip if not leader — only the leader can commit membership changes.
            if (node.role.value !is RaftRole.Leader) continue
            // Skip if already evicted (e.g. duplicate signal from detector + vacate).
            if (lostId !in voters) continue

            // Cancel the stale detector job (if still running — vacate path cancels it first).
            detectorJobs.remove(lostId)?.cancel()

            // Remove the dead voter and commit the shrunken config.
            voters.remove(lostId)
            evictedVoterIds += lostId
            changeMembershipWithRetry(node, ClusterConfig(voters = voters.toSet(), learners = spectatorIds.toSet()))

            // Re-open admission so a new gameJoin can take the freed seat.
            presence.declareAdmissionOpen()

            // Admit exactly one replacement voter, excluding evicted peers so a gracefully-departing
            // peer (still connected) cannot immediately reclaim its own freed seat.
            val votersBeforeAdmit = voters.toSet()
            admitVotersUntil(
                node,
                seam,
                voters,
                spectatorIds + evictedVoterIds,
                target = votersBeforeAdmit.size + 1,
                presence,
            )

            // Re-close admission with the refreshed voter set.
            presence.declareAdmissionClosed(voters)

            // Start a liveness detector for the replacement voter (the one not in votersBeforeAdmit).
            val newVoterId = voters.firstOrNull { it !in votersBeforeAdmit }
            if (newVoterId != null) {
                detectorJobs[newVoterId] = launchDetectorFor(newVoterId)
            }
        }
    }
}

/**
 * Launches a [HeartbeatPartitionDetector] for [voterId] and returns its [Job].
 *
 * On [PartitionEvent.PeerLost], sends [voterId] to [evictions] and stops.
 * [PartitionEvent.PeerUnresponsive] and [PartitionEvent.PeerRecovered] are no-ops at this layer
 * (Raft's own replication tracks liveness; the eviction gate is [PeerLost] only).
 *
 * `internal` (not `private`) so the per-voter teardown contract is unit-testable: cancelling the
 * returned [Job] must tear down *all* of the detector's coroutines, not just the events collector.
 */
internal fun CoroutineScope.launchDetectorFor(
    voterId: NodeId,
    heartbeatSeam: Seam,
    rawLiveness: MutableSharedFlow<Swatch>,
    evictions: Channel<NodeId>,
    config: HeartbeatConfig,
    clock: () -> Instant,
): Job {
    val peerId = PeerId(voterId.value)
    val perPeerSeam = GamePerPeerSeam(heartbeatSeam, peerId, rawLiveness)
    val detector = HeartbeatPartitionDetector(perPeerSeam, peerId, config, clock)
    // Own all of the detector's coroutines under one umbrella job: `detector.start(this)` makes
    // the heartbeat loop and the inbound collector (which subscribes to the never-completing
    // [rawLiveness]) children of this launch, so cancelling the returned job tears the whole
    // detector down. Storing only the events-collector would orphan the other two past the
    // voter's eviction, on the long-lived session scope (#1001-class leak).
    return launch {
        detector.start(this)
        detector.events.collect { event ->
            if (event is PartitionEvent.PeerLost) {
                evictions.trySend(voterId)
            }
        }
    }
}
