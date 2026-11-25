package us.tractat.kuilt.game

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.game.VoterLivenessMonitor")

/** A request to the eviction loop to free [voter]'s seat. */
internal sealed interface EvictionRequest {
    val voter: NodeId

    /**
     * [detector] lost [voter]. A detector's report about the seat it was watching: it counts only
     * while [detector] is still the one registered for [voter] (#3672).
     */
    class Lost(override val voter: NodeId, val detector: Job) : EvictionRequest

    /**
     * [voter] said it is leaving. Its own statement rather than a detector's report, so no detector
     * is checked: it counts while [voter] is still a voter and the board still shows it vacating
     * (#3684). A vacate it has taken back by the time the eviction loop reaches this does not count.
     */
    class Vacated(override val voter: NodeId) : EvictionRequest
}

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
 * On [PartitionEvent.PeerLost] (leader only): evicts the dead voter through [HostMembership.evict],
 * re-opens admission, runs [admitVotersUntil] for one replacement, closes admission again if that
 * filled all `peerCount` seats, then starts a fresh detector for the new voter. Graceful leave ([GamePresence.vacaters]) triggers eviction immediately without
 * waiting the reconnect window.
 *
 * Non-leader nodes receive [PartitionEvent.PeerLost] but take no action — Raft's commit-majority
 * gate means only the leader can commit the membership change.
 *
 * A loss names the detector that reported it, and the eviction loop acts on it only while that
 * detector is still the voter's (#3672). A voter evicted while the host still fills seats
 * ([ReturnPolicy.Quorum]) can be admitted again and get a new detector, and a loss about his earlier
 * seat can still be on its way, from a detector's collector that lost its thread before sending it.
 * Acted on, it would evict a voter whose link is healthy, and the eviction loop's exclusion list
 * keeps him out of the seat for good. A vacate is the voter's own word, not a detector's, so it is
 * checked against the voter set and against the board: it counts only while [GamePresence.vacaters]
 * still names him when the eviction loop acts on it (#3684). The loop can sit in [admitVotersUntil]
 * for as long as no replacement comes, and a vacate queued behind that wait may have been taken back.
 *
 * All coroutines launch on the injected [scope]; cancelling it tears them all down (the #1001-class
 * leak guard).
 *
 * @param scope The session scope that owns every coroutine this monitor launches.
 * @param node The leader [RaftNode] (may or may not currently hold the leader role).
 * @param seam The game seam (used for [PeerId] extraction and [nextVoterPeer]).
 * @param mux The [MuxSeam] wrapping [seam]; the liveness channel ([HEARTBEAT_CHANNEL]) is a view on it.
 * @param membership The host's one membership writer, shared with the admission and spectator loops;
 *   every eviction and replacement goes through it, so neither drops a spectator or another voter.
 * @param peerCount The number of seats, host included. A replacement closes admission again only once
 *   the roster has this many voters; below it a seat is still open for a latecomer (#3683).
 * @param presence The [GamePresence] instance; used to detect vacaters and re-open/re-close admission.
 * @param config [HeartbeatConfig] driving ping interval, timeout, and reconnect window.
 * @param clock Clock for liveness measurements; injected for virtual-time test determinism.
 */
internal class VoterLivenessMonitor(
    private val scope: CoroutineScope,
    private val node: RaftNode,
    private val seam: Seam,
    private val mux: MuxSeam,
    private val membership: HostMembership,
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

    // Serialised evictions: detector coroutines and the vacate watcher send requests here; the
    // eviction loop processes them one at a time on this scope. Channel.UNLIMITED so detector jobs
    // never block.
    private val evictions = Channel<EvictionRequest>(Channel.UNLIMITED)

    // Guards [detectorJobs]: the vacate watcher reads it while the eviction loop writes it.
    private val lock = reentrantLock()

    // Active detector job per voter, under [lock]. Only [watch] adds an entry, and only the eviction
    // loop removes one, in the section that checks a request against it.
    private val detectorJobs = mutableMapOf<NodeId, Job>()

    /**
     * Starts liveness monitoring: fans the heartbeat channel, launches one detector per initial
     * voter (excluding self), and launches the graceful-leave watcher and the eviction loop. All
     * launches are children of [scope], so cancelling it tears everything down.
     */
    fun start() {
        launchLivenessFan()

        // Start one detector per initial admitted voter (excluding self).
        membership.voters.value.filter { it != self }.forEach { voterId -> watch(voterId) }

        // Graceful-leave watcher: vacate signals bypass the reconnect window.
        scope.launch { watchVacaters() }

        // Eviction loop: process one PeerLost at a time.
        scope.launch { evictAndReopenAdmission() }
    }

    /** **Test-visibility.** The job of the detector registered for [voter], or null. */
    internal fun detectorJobForTest(voter: NodeId): Job? = lock.withLock { detectorJobs[voter] }

    /**
     * **Test-visibility.** Hand [request] to the eviction loop. A test passes a [EvictionRequest.Lost]
     * naming a detector a rejoin has since replaced, to stand in for a loss about the voter's earlier
     * seat that reaches the eviction loop late (#3672), or a [EvictionRequest.Vacated] the watcher
     * would send. No production caller uses it.
     */
    internal fun requestEvictionForTest(request: EvictionRequest) {
        evictions.trySend(request)
    }

    /**
     * Starts a detector for [voterId] and registers it. It is registered before it starts, so a
     * loss it reports is never judged against the detector it replaced (#3672).
     *
     * Delegates to the [CoroutineScope.launchDetectorFor] free function on [scope] so the job owns
     * *all* of the detector's coroutines (its heartbeat loop and inbound collector), not just the
     * events collector — cancelling the job tears the whole detector down (#1001-class leak guard).
     */
    private fun watch(voterId: NodeId) {
        val job = scope.launchDetectorFor(
            voterId,
            heartbeatSeam,
            rawLiveness,
            evictions,
            config,
            clock,
            start = CoroutineStart.LAZY,
        )
        lock.withLock { detectorJobs.put(voterId, job) }?.cancel()
        job.start()
    }

    private fun launchLivenessFan() {
        scope.launch { heartbeatSeam.incoming.collect { rawLiveness.emit(it) } }
    }

    /**
     * Watches for graceful-leave vacate signals on [presence] and triggers immediate eviction.
     *
     * Polls [GamePresence.vacaters] on every Quilter state change (announced flow). When a new
     * vacater is seen that is a current voter, stops its detector and asks for its eviction, to
     * bypass the reconnect window. The detector stays registered until the eviction loop acts.
     */
    private suspend fun watchVacaters() {
        val seenVacaters = mutableSetOf<NodeId>()
        presence.announced.collect {
            val newVacaters = presence.vacaters()
                .map { NodeId(it.value) }
                .filter { it in membership.voters.value && it !in seenVacaters && it != self }
            newVacaters.forEach { vacaterId ->
                seenVacaters += vacaterId
                // Stop pinging a voter that is leaving voluntarily.
                lock.withLock { detectorJobs[vacaterId] }?.cancel()
                evictions.trySend(EvictionRequest.Vacated(vacaterId))
            }
        }
    }

    /**
     * Processes evictions from [evictions]: removes the dead voter, re-opens admission for one
     * replacement, then starts a fresh detector for the replacement.
     *
     * Only the Raft **leader** changes membership; non-leaders skip the eviction.
     * If leadership has transferred by the time eviction fires, the new leader's own loop handles
     * the eviction — or the evicted peer's seat remains open until another PeerLost fires.
     *
     * A loss counts only while the detector that reported it is still the voter's, checked in the
     * section that unregisters it (#3672). This loop is the only one that unregisters a detector or
     * evicts a voter, so nothing can replace the detector between that check and the eviction.
     *
     * A vacate counts only while the board still shows the voter vacating, read in the same section
     * (#3684). One he has taken back is dropped, and if the watcher stopped his detector when it saw
     * the vacate, a new one watches him, so a seat that stays taken is never left unwatched.
     */
    private suspend fun evictAndReopenAdmission() {
        // Peers that have been evicted from the voter set — excluded from re-admission so a gracefully
        // departing peer (still connected) is not immediately re-admitted to its own freed seat.
        val evictedVoterIds = mutableSetOf<NodeId>()

        for (request in evictions) {
            val lostId = request.voter
            // Skip if not leader — only the leader can commit membership changes.
            if (node.role.value !is RaftRole.Leader) continue
            // Skip if already evicted (e.g. duplicate signal from detector + vacate).
            if (lostId !in membership.voters.value) continue

            // Skip a loss about an earlier seat: the detector that reported it has been unregistered.
            var stopped: Job? = null
            var unwatched = false
            val current = lock.withLock {
                val current = when (request) {
                    is EvictionRequest.Lost -> detectorJobs[lostId] === request.detector
                    // Skip a vacate the voter has taken back: read the board now, not when it was queued.
                    is EvictionRequest.Vacated -> presence.vacaters().any { it.value == lostId.value }
                }
                if (current) {
                    stopped = detectorJobs.remove(lostId)
                } else if (request is EvictionRequest.Vacated) {
                    // Cancelled means the watcher stopped it for this vacate; a detector that lost him
                    // completes instead, and its own loss is on the way.
                    unwatched = detectorJobs[lostId]?.isCancelled == true
                }
                current
            }
            if (!current) {
                when (request) {
                    is EvictionRequest.Lost ->
                        logger.debug { "eviction.stale-detector voter=${lostId.value}: the detector that lost it no longer watches it" }
                    is EvictionRequest.Vacated ->
                        logger.debug { "eviction.stale-vacate voter=${lostId.value}: no longer vacating, rewatch=$unwatched" }
                }
                if (unwatched) watch(lostId)
                continue
            }
            // Cancel the detector (if still running — vacate path cancels it first).
            stopped?.cancel()

            // Remove the dead voter and commit the shrunken config.
            evictedVoterIds += lostId
            membership.evict(lostId)

            // Re-open admission so a new gameJoin can take the freed seat.
            presence.declareAdmissionOpen()

            // Admit exactly one replacement voter, excluding evicted peers so a gracefully-departing
            // peer (still connected) cannot immediately reclaim its own freed seat.
            val votersBeforeAdmit = membership.voters.value
            admitVotersUntil(
                seam,
                membership,
                target = votersBeforeAdmit.size + 1,
                presence,
                excluded = evictedVoterIds.toSet(),
            )

            // Re-close admission only if the replacement filled the roster. Under ReturnPolicy.Quorum a
            // seat can still be open, and the latecomer loop closes admission when it fills it (#3683).
            val voters = membership.voters.value
            if (voters.size >= peerCount) presence.declareAdmissionClosed(voters)

            // Start a liveness detector for the replacement voter (the one not in votersBeforeAdmit).
            val newVoterId = membership.voters.value.firstOrNull { it !in votersBeforeAdmit }
            if (newVoterId != null) watch(newVoterId)
        }
    }
}

/**
 * Launches a [HeartbeatPartitionDetector] for [voterId] and returns its [Job].
 *
 * On [PartitionEvent.PeerLost], sends [evictions] an [EvictionRequest.Lost] naming the returned
 * [Job] as the detector that lost [voterId], and stops.
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
    evictions: Channel<EvictionRequest>,
    config: HeartbeatConfig,
    clock: () -> Instant,
    start: CoroutineStart = CoroutineStart.DEFAULT,
): Job {
    val peerId = PeerId(voterId.value)
    val perPeerSeam = GamePerPeerSeam(heartbeatSeam, peerId, rawLiveness)
    val detector = HeartbeatPartitionDetector(perPeerSeam, peerId, config, clock)
    // Own all of the detector's coroutines under one umbrella job: `detector.start(this)` makes
    // the heartbeat loop and the inbound collector (which subscribes to the never-completing
    // [rawLiveness]) children of this launch, so cancelling the returned job tears the whole
    // detector down. Storing only the events-collector would orphan the other two past the
    // voter's eviction, on the long-lived session scope (#1001-class leak).
    return launch(start = start) {
        val self = coroutineContext.job
        detector.start(this)
        detector.events.collect { event ->
            if (event is PartitionEvent.PeerLost) {
                evictions.trySend(EvictionRequest.Lost(voterId, self))
            }
        }
    }
}
