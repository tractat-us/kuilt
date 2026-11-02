package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.changeMembershipWithRetry
import kotlin.time.Instant

/**
 * Watches over the live voter set for a hosted game and keeps it whole.
 *
 * When a voter drops off the network — a lost connection, or a deliberate "I'm leaving" — this
 * monitor notices, removes that seat from the game's membership, re-opens the door for one
 * replacement, and starts watching the newcomer in turn. It is created and started once, on the
 * host's session scope, by [gameHost] when the caller opts into liveness monitoring; cancelling
 * that scope tears the whole monitor down with it, and so does [GameSession.close], through the job
 * [start] returns.
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
 * the new voter. A graceful leave ([GamePresence.vacating]) takes the same path as soon as the frame
 * arrives, without waiting the reconnect window.
 *
 * A monitor on a non-leader receives both signals and takes no action — Raft's commit-majority gate
 * means only the leader can commit the membership change.
 *
 * **A membership change here can fail without anything being wrong with the host.** The voter being
 * removed may be one the change still needs: leaving a two-seat table, or a table where everyone else
 * has gone too, takes the old config's majority, and that voter has gone. The change then fails with
 * the node's leadership. So a lost leadership ends that one eviction and not the host's scope, and the
 * voter set is read back from [RaftNode.membership], since the change's outcome is unknown. Any other
 * failure, such as [changeMembershipWithRetry] giving up, still fails the scope loudly.
 *
 * All coroutines launch under one job, a child of the injected [scope]; cancelling either tears them
 * all down (the #1001-class leak guard).
 *
 * @param scope The session scope that parents every coroutine this monitor launches.
 * @param node The leader [RaftNode] (may or may not currently hold the leader role).
 * @param seam The game seam (used for [PeerId] extraction and [nextVoterPeer]).
 * @param mux The [MuxSeam] wrapping [seam]; the liveness channel ([HEARTBEAT_CHANNEL]) is a view on it.
 * @param voters The mutable live voter set (shared with the admission loop; this monitor reads and
 *   writes it only from its single background eviction coroutine).
 * @param spectatorIds The mutable spectator NodeId set (passed to [ClusterConfig] to preserve learners).
 * @param peerCount Total configured voter count; used to re-open admission to exactly one replacement.
 * @param presence The [GamePresence] instance; used to detect vacaters and re-open/re-close admission.
 * @param config [HeartbeatConfig] driving ping interval, timeout, and reconnect window.
 * @param clock Clock for liveness measurements; injected for virtual-time test determinism.
 */
internal class VoterLivenessMonitor(
    scope: CoroutineScope,
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

    // A plain Job rather than a supervisor, so a failure that does escape still reaches [scope].
    private val monitorJob = Job(scope.coroutineContext[Job])
    private val monitorScope = scope + monitorJob

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
     * launches are children of the returned job, so cancelling it tears everything down.
     */
    fun start(): Job {
        launchLivenessFan()

        // Start one detector per initial admitted voter (excluding self).
        voters.filter { it != self }.forEach { voterId ->
            detectorJobs[voterId] = launchDetectorFor(voterId)
        }

        // Graceful-leave watcher: vacate signals bypass the reconnect window.
        monitorScope.launch { watchVacaters() }

        // Eviction loop: process one PeerLost at a time.
        monitorScope.launch { evictAndReopenAdmission() }

        return monitorJob
    }

    private fun launchLivenessFan() {
        monitorScope.launch { heartbeatSeam.incoming.collect { rawLiveness.emit(it) } }
    }

    /**
     * Launches a [HeartbeatPartitionDetector] for [voterId] and returns its [Job].
     *
     * Delegates to the [CoroutineScope.launchDetectorFor] free function on [monitorScope] so the returned
     * job owns *all* of the detector's coroutines (its heartbeat loop and inbound collector), not
     * just the events collector — cancelling the job tears the whole detector down (#1001-class
     * leak guard).
     */
    private fun launchDetectorFor(voterId: NodeId): Job =
        monitorScope.launchDetectorFor(voterId, heartbeatSeam, rawLiveness, evictions, config, clock)

    /**
     * Watches for graceful-leave vacate signals on [presence] and triggers immediate eviction.
     *
     * Collects [GamePresence.vacating] and sends each new vacater's [NodeId] to [evictions], to bypass
     * the reconnect window. That is all it does. Whether the signal can be acted on is for the eviction
     * loop, which is the one coroutine that reads [voters] and [detectorJobs], and which decides when it
     * reaches the signal rather than when it arrived. In particular the voter's detector keeps running
     * until an eviction is actually under way, or a vacate heard by a non-leader would stop the only
     * thing still able to free the seat.
     */
    private suspend fun watchVacaters() {
        val seenVacaters = mutableSetOf<NodeId>()
        presence.vacating.collect { vacating ->
            val newVacaters = vacating
                .map { NodeId(it.value) }
                .filter { it !in seenVacaters && it != self }
            newVacaters.forEach { vacaterId ->
                seenVacaters += vacaterId
                evictions.trySend(vacaterId)
            }
        }
    }

    /**
     * Processes evictions from [evictions]: removes the dead voter, re-opens admission for one
     * replacement, then starts a fresh detector for the replacement.
     *
     * Only the Raft **leader** calls [changeMembershipWithRetry]; non-leaders drop the signal. Only
     * [gameHost] runs this monitor, so no other peer picks it up: a vacate dropped here costs the seat
     * the reconnect window, and a [PartitionEvent.PeerLost] dropped here leaves the seat taken, because
     * that voter's detector has already stopped and nothing else will fire for it.
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

            // Cancel the stale detector job (if still running).
            detectorJobs.remove(lostId)?.cancel()

            try {
                evictAndAdmitReplacement(lostId, evictedVoterIds)
            } catch (_: LeadershipLostException) {
                resyncAfterFailedChange()
            } catch (_: NotLeaderException) {
                resyncAfterFailedChange()
            }
        }
    }

    private suspend fun evictAndAdmitReplacement(lostId: NodeId, evictedVoterIds: MutableSet<NodeId>) {
        // Remove the dead voter and commit the shrunken config.
        voters.remove(lostId)
        evictedVoterIds += lostId
        node.changeMembershipWithRetry(ClusterConfig(voters = voters.toSet(), learners = spectatorIds.toSet()))

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

    /**
     * A change failed with the leadership, and it may or may not have landed. Takes the voter set from
     * [node] rather than from what was attempted, and matches the detectors to it: a voter the change
     * left in place is watched again, so its seat can still come free at the end of a window.
     *
     * [RaftNode.membership] is the latest config in the log, committed or not, so this can name a set
     * that a later leader replaces. Nothing here follows that.
     */
    private fun resyncAfterFailedChange() {
        val current = node.membership.value.voters
        voters.retainAll(current)
        voters.addAll(current)
        (detectorJobs.keys - voters).forEach { gone -> detectorJobs.remove(gone)?.cancel() }
        (voters - detectorJobs.keys - self).forEach { voterId -> detectorJobs[voterId] = launchDetectorFor(voterId) }
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
