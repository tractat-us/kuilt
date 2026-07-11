package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.changeMembershipWithRetry
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backoff between core-admission attempts after a failed membership change, so a
 * transiently-stale leader (role flow still reads Leader while the engine already rejects
 * `changeMembership`) cannot hot-loop. Matches [changeMembershipWithRetry]'s internal cadence.
 */
private val CORE_ADMISSION_RETRY_BACKOFF = 200.milliseconds

/**
 * Core-side learner admission for the server-core placement: whenever this
 * node is the core leader and a connected session peer is neither a core voter nor already an
 * admitted learner, commit a membership change adding it as a learner.
 *
 * Launched by the game bootstrap on every core member; the role gate inside the [combine] ensures
 * only the current leader acts, so leadership moving between core nodes hands the loop over
 * automatically. Runs for the life of the bootstrap caller's scope.
 *
 * The admission domain is [seam]'s roster — the loop admits exactly the peers the seam can see.
 * Under the game-per-room composition (the game-per-room bootstrap) that seam is one game's room, so
 * each game admits only its own players: per-game admission falls out of the room's structural
 * isolation rather than any bookkeeping here.
 *
 * A failed membership change (leadership moved between observation and call, or the bounded
 * retry gave up) is tolerated and re-attempted after [CORE_ADMISSION_RETRY_BACKOFF] — the
 * surviving leader's own loop takes over, and a genuinely stuck cluster still surfaces as the
 * session making no progress rather than a crashed bootstrap scope.
 */
public fun CoroutineScope.launchCoreLearnerAdmission(
    node: RaftNode,
    seam: Seam,
    core: Set<NodeId>,
) {
    launch {
        while (true) {
            val next = combine(seam.peers, node.role, node.membership) { peers, role, membership ->
                if (role !is RaftRole.Leader) return@combine null
                peers.map { NodeId(it.value) }
                    .firstOrNull { it !in core && it !in membership.learners }
            }.filterNotNull().first()

            val current = node.membership.value
            runCatchingCancellable {
                node.changeMembershipWithRetry(
                    ClusterConfig(voters = current.voters, learners = current.learners + next),
                )
            }.onFailure { delay(CORE_ADMISSION_RETRY_BACKOFF) }
        }
    }
}

/**
 * Cross-server learner admission for the federated server-core placement — the federated
 * generalisation of [launchCoreLearnerAdmission].
 *
 * A federated game runs one Raft cluster whose players are spread over several core servers, each
 * connected to the players nearest it. A leader's [seam] roster is only its *own* local players plus
 * the other servers, so [launchCoreLearnerAdmission] alone would never admit a player behind a
 * *different* server — that player is never added to the config, so the leader never replicates to it
 * and its `matchIndex` can never advance. This loop closes that gap by having every core server share
 * its local roster with every other core member, so the leader admits from the **union** of all
 * servers' rosters.
 *
 * Launched on every core member (self-gated by the placement to `self ∈ [core]`); it runs three
 * coroutines on this scope:
 *
 * - **Publish.** Unicast (never broadcast) this server's local players (`seam.peers − core`) to the
 *   other connected core members over [rosterChannel]. Two structural triggers, both timer-free:
 *   (a) whenever a **core member newly appears** in [seam.peers], send to *that* member — connection
 *   precedes peer-visibility, so the arriving member's tag-6 collector is already subscribed by the
 *   time it shows up here, and this send lands even at a simultaneous boot with the far player already
 *   attached; and (b) whenever this server's **local roster changes** (a player joins/leaves), send
 *   the new roster to *all* connected core members. Every send is a single-addressee [Seam.sendTo],
 *   never a fan-out.
 * - **Receive & reactive re-publish.** Collect [rosterChannel], accepting a roster frame **only** if
 *   its `sender` is a core member (`NodeId(sender.value) ∈ core`) — the first-hop authenticity check,
 *   parallel to the relay's spoof validation, that stops a spoke player from injecting membership.
 *   Accepted rosters are kept per sender in a [MutableStateFlow]. Whenever a frame carries *new*
 *   information (a first-heard sender or a changed roster) this node re-publishes its own roster — a
 *   second self-heal for [rosterChannel]'s best-effort (`replay = 0`) subscribe-race, complementing
 *   the appearance trigger above. The receive collector runs under a **retry-with-backoff** loop, so a
 *   transient failure never permanently stops this node from learning rosters.
 * - **Admit.** Whenever this node is the leader, admit the first peer in
 *   `(seam.peers − core) ∪ union(remote rosters)` that is neither a core voter nor already a learner —
 *   **add-only, learners-only** (never removes, never touches the voter set). The role gate hands the
 *   loop between core nodes on a leadership change automatically; because rosters flow to *every* core
 *   member continuously, a new leader already holds every server's roster and is never blind to a far
 *   player (H2).
 *
 * A failed membership change is tolerated and re-attempted after [CORE_ADMISSION_RETRY_BACKOFF],
 * exactly as in [launchCoreLearnerAdmission].
 */
public fun CoroutineScope.launchFederatedCoreAdmission(
    node: RaftNode,
    seam: Seam,
    rosterChannel: Seam,
    core: Set<NodeId>,
) {
    val self = NodeId(seam.selfId.value)
    // sender NodeId → the local roster that core server last published. Only the leader acts on it,
    // but every core member maintains it so a leadership change hands over a fully-populated view.
    val remoteRosters = MutableStateFlow<Map<NodeId, Set<NodeId>>>(emptyMap())

    // Unicast this server's current local roster (seam.peers − core) to [targets]. Single-addressee
    // sends only — never a broadcast/fan-out.
    suspend fun publishLocalRosterTo(targets: Set<NodeId>) {
        if (targets.isEmpty()) return
        val payload = encodeRoster(seam.peers.value.mapTo(mutableSetOf()) { NodeId(it.value) }.apply { removeAll(core) })
        for (member in targets) {
            runCatchingCancellable { rosterChannel.sendTo(PeerId(member.value), payload) }
        }
    }

    // Send our current roster to every connected core member (used by the reactive re-publish path).
    suspend fun publishLocalRoster() =
        publishLocalRosterTo(seam.peers.value.mapTo(mutableSetOf()) { NodeId(it.value) }.filterTo(mutableSetOf()) { it in core && it != self })

    // Receive: accept a roster frame only from a core sender (first-hop authenticity); on genuinely
    // new information, re-publish our own roster to self-heal the best-effort subscribe-race. Wrapped
    // in a retry-with-backoff loop so a transient throw does not permanently kill reception (M3).
    launch {
        while (true) {
            val outcome = runCatchingCancellable {
                rosterChannel.incoming.collect { swatch ->
                    val sender = swatch.sender?.let { NodeId(it.value) } ?: return@collect
                    if (sender !in core) return@collect // a spoke must not be able to inject membership
                    val roster = runCatchingCancellable { decodeRoster(swatch.toByteArray()) }.getOrNull()
                        ?: return@collect
                    if (remoteRosters.value[sender] == roster) return@collect // nothing new — no churn
                    remoteRosters.update { it + (sender to roster) }
                    publishLocalRoster()
                }
            }
            // Clean completion means the channel closed (the seam tore) — stop. A transient failure is
            // retried after a backoff, mirroring the admit loop, so this node keeps learning rosters.
            if (outcome.isSuccess) break
            delay(CORE_ADMISSION_RETRY_BACKOFF)
        }
    }

    // Publish: (a) to a newly-appeared core member (its collector is up by the time it is visible
    // here), and (b) to all core members when our local roster changes.
    launch {
        var knownCore = emptySet<NodeId>()
        var lastLocal: Set<NodeId>? = null
        seam.peers.collect { peers ->
            val ids = peers.mapTo(mutableSetOf()) { NodeId(it.value) }
            val coreNow = ids.filterTo(mutableSetOf()) { it in core && it != self }
            val local = ids.filterTo(mutableSetOf()) { it !in core }
            val appeared = coreNow - knownCore
            val localChanged = local != lastLocal
            // On a local-roster change every core member needs the update; otherwise only the
            // newly-appeared members need our current roster.
            publishLocalRosterTo(if (localChanged) coreNow else appeared)
            knownCore = coreNow
            lastLocal = local
        }
    }

    // Admit: leader-only, from the union of local + all remote rosters. Add-only, learners-only.
    launch {
        while (true) {
            val next = combine(
                seam.peers,
                node.role,
                node.membership,
                remoteRosters,
            ) { peers, role, membership, rosters ->
                if (role !is RaftRole.Leader) return@combine null
                val candidates = peers.map { NodeId(it.value) } + rosters.values.flatten()
                candidates.firstOrNull { it !in core && it !in membership.learners }
            }.filterNotNull().first()

            val current = node.membership.value
            runCatchingCancellable {
                node.changeMembershipWithRetry(
                    ClusterConfig(voters = current.voters, learners = current.learners + next),
                )
            }.onFailure { delay(CORE_ADMISSION_RETRY_BACKOFF) }
        }
    }
}

/** CBOR codec for a core server's published local roster — a set of player [NodeId]s. */
@OptIn(ExperimentalSerializationApi::class)
private val rosterCbor = Cbor { ignoreUnknownKeys = true }

@OptIn(ExperimentalSerializationApi::class)
private val rosterSerializer = SetSerializer(NodeId.serializer())

/** Encode [roster] as the CBOR payload carried on the roster-exchange channel. */
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeRoster(roster: Set<NodeId>): ByteArray =
    rosterCbor.encodeToByteArray(rosterSerializer, roster)

/** Decode a roster-exchange payload back into a roster; may throw on malformed [bytes]. */
@OptIn(ExperimentalSerializationApi::class)
internal fun decodeRoster(bytes: ByteArray): Set<NodeId> =
    rosterCbor.decodeFromByteArray(rosterSerializer, bytes)
