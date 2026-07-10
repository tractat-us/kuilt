package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.cluster.playerRelayTransport
import us.tractat.kuilt.cluster.serverRelayTransport
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.changeMembershipWithRetry
import us.tractat.kuilt.raft.raftNode
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which peers hold the voter seats of a game's consensus cluster.
 *
 * This is the observable half of a [ConsensusPlacement]: the bootstrap entry points read it to
 * decide how session peers obtain a seat — promoted into voter seats ([SessionPeers], today's
 * appoint-the-host and roster-given behaviour) or riding as learners under a fixed external
 * voter core ([CoreVoters], the server-core topology).
 */
public sealed interface AuthoritySeating {
    /**
     * The session's own peers take the voter seats — the phone-host / roster-elected placement.
     *
     * [gameHost] grows the voter set by promoting each admitted joiner; [gameNode] seats the
     * given roster directly. Quorum lives on the players' devices.
     */
    public data object SessionPeers : AuthoritySeating

    /**
     * A fixed external [core] holds every voter seat; session peers ride as admitted learners.
     *
     * This is the server-core placement: quorum and durable state live on the [core] machines
     * (all of which vote in every game), so the game survives the loss of a minority of core
     * nodes and any number of players. Session peers receive the committed log and propose via
     * learner→leader forwarding — the consuming layer ([TurnSequencer], [GameSession.appChannel])
     * is identical to [SessionPeers].
     */
    public data class CoreVoters(public val core: Set<NodeId>) : AuthoritySeating {
        init {
            require(core.isNotEmpty()) { "core voter set must be non-empty" }
        }
    }
}

/**
 * Everything a game bootstrap has wired by the time it asks a [ConsensusPlacement] for its
 * [RaftNode].
 *
 * A placement that constructs a real node uses [transport] (the session seam's dedicated Raft
 * mux channel) plus the caller-supplied [storage]/[raftConfig]/[identity]; [sessionMembership]
 * is the initial membership the bootstrap path would have used on its own — the full voter
 * roster for [gameNode], a singleton-self cluster for [gameHost], a self-only learner for
 * [gameJoin]/[gameSpectate]. A placement that hands over a pre-built node
 * ([ConsensusPlacement.preBuilt]) is free to ignore all of it.
 */
public class ConsensusBinding(
    /** This peer's [NodeId] — derived from the session seam's `selfId`. */
    public val self: NodeId,
    /** The Raft messaging seam, already muxed onto the session's dedicated consensus channel. */
    public val transport: RaftTransport,
    /** The initial membership the bootstrap path would have used absent a placement override. */
    public val sessionMembership: ClusterConfig,
    /** Durable Raft state supplied by the bootstrap caller. */
    public val storage: RaftStorage,
    /** Timing and behaviour parameters supplied by the bootstrap caller. */
    public val raftConfig: RaftConfig,
    /** Raft §8 dedup identity supplied by the bootstrap caller. */
    public val identity: ClientIdentity,
    /**
     * The dedicated **relay** channel — the `RAFT_RELAY` mux tag carved over the *same* session
     * seam as [transport], so its `selfId`/peer ids are byte-for-byte the ids [transport]'s
     * [NodeId]s derive from (the first Task-1-review contract: a node's relay-channel `PeerId`
     * string must equal its Raft `NodeId` string).
     *
     * Only the federated placement ([ConsensusPlacement.federatedCore]) reads this — it wraps
     * [transport] in a routing decorator that carries cross-server Raft frames over this channel.
     * Every other placement ([ConsensusPlacement.SessionOwned] / [ConsensusPlacement.serverCore] /
     * [ConsensusPlacement.preBuilt]) ignores it entirely; provisioning the channel is inert (a mux
     * view that never sends or receives a frame produces no wire traffic), so the off-federation
     * bootstrap is byte-identical.
     */
    public val relayChannel: Seam,
)

/**
 * Where a game session's consensus authority lives — an injectable, bootstrap-time choice.
 *
 * Every bootstrap entry point ([gameNode], [gameHost], [gameJoin], [gameSpectate], [gameHosted])
 * obtains its [RaftNode] through a placement instead of constructing one internally. The
 * session-consuming layer — [TurnSequencer], [GameSession.appChannel], Quilter riding an app
 * channel — is identical regardless of which placement is injected; only *where quorum lives*
 * changes:
 *
 * - [SessionOwned] (the default): consensus lives among the session's own peers. This is
 *   today's behaviour, byte-for-byte — the appoint-the-host path grows voters from joiners,
 *   the roster-given path seats the known roster.
 * - [serverCore]: consensus lives on a fixed core of server nodes, **all of which vote in
 *   every game**; session peers ride as learners. Bootstrap via [gameNode].
 * - [preBuilt]: the session drives a node the caller already constructed — the seam that lets
 *   a consumer bootstrap the full game stack against a test double (e.g. a `FakeRaftNode`
 *   pinned to `Leader`) under pure virtual time, with no real Raft engine anywhere.
 *
 * A future `gameId`-sharded placement (hash the game id onto k of M core nodes) is another
 * [AuthoritySeating.CoreVoters] instance with a narrower core — it slots in behind this
 * interface without touching session-consuming code.
 */
public interface ConsensusPlacement {
    /** Which peers hold voter seats under this placement. */
    public val seating: AuthoritySeating

    /**
     * Build — or hand over — the [RaftNode] whose log this session drives.
     *
     * Called exactly once per bootstrap, after the session seam has been muxed and [binding]
     * fully wired. A constructing placement ties the node's lifetime to [scope] (the bootstrap
     * caller's scope) via [raftNode]; a pre-built placement returns its node unchanged and the
     * caller retains lifecycle ownership.
     */
    public fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode

    public companion object {
        /**
         * The session's own peers hold consensus — today's behaviour for every bootstrap path.
         *
         * Constructs the node over [ConsensusBinding.transport] with the unmodified
         * [ConsensusBinding.sessionMembership], storage, config, and identity — exactly the
         * construction each entry point performed before placements existed.
         */
        public val SessionOwned: ConsensusPlacement = object : ConsensusPlacement {
            override val seating: AuthoritySeating = AuthoritySeating.SessionPeers

            override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode =
                scope.raftNode(
                    binding.sessionMembership,
                    binding.transport,
                    binding.storage,
                    binding.raftConfig,
                    binding.identity,
                )
        }

        /**
         * All-servers-vote server-core placement: [core] holds every voter seat of every game.
         *
         * A peer whose id is in [core] seats itself as a voter (`voters = core`); any other peer
         * starts as a self-declared learner (`voters = core, learners = {self}`) and is admitted
         * into the replicated config by the core leader's learner-admission loop (launched by
         * [gameNode] on core members). Players propose via learner→leader forwarding and receive
         * the committed log — the same [TurnSequencer]/[GameSession] code as [SessionOwned].
         *
         * Bootstrap via [gameNode] with `placement = serverCore(core)` on **every** peer, core
         * and player alike — or, for many concurrent games over one connection set, via
         * [gameNodeRoom] per game (the game-per-room composition: each game's room seam scopes
         * the admission loop to exactly that game's players). The appoint-the-host paths
         * ([gameHost]/[gameJoin]/[gameSpectate]) reject this seating — their admission
         * machinery promotes session peers to voters, which contradicts a fixed core.
         *
         * @param core The [NodeId]s of the server core — every one of them votes in this game.
         *   Must be non-empty.
         * @sample us.tractat.kuilt.game.sampleServerCorePlacement
         */
        public fun serverCore(core: Set<NodeId>): ConsensusPlacement = object : ConsensusPlacement {
            override val seating: AuthoritySeating = AuthoritySeating.CoreVoters(core)

            override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode =
                scope.raftNode(
                    coreMembership(binding.self, core),
                    binding.transport,
                    binding.storage,
                    binding.raftConfig,
                    binding.identity,
                )
        }

        /**
         * The **federated** server-core placement: like [serverCore] — a fixed [core] holds every
         * voter seat and session peers ride as learners — but each node's Raft transport is wrapped
         * in a cross-server routing decorator so a leader on one server can reach a player behind a
         * *different* server. This is the placement [gameNodeRoomFederated] uses, and the one a
         * federated player passes to [gameNodeRoom].
         *
         * A federated game runs one Raft cluster whose members are spread over several servers: the
         * servers form a fully-meshed core, each player connects to whichever server is nearest, and
         * the committed log must reach *every* player regardless of which server leads. Plain
         * [serverCore] can only deliver to nodes a server is directly wired to — a player behind
         * another server never receives `AppendEntries`. This placement fixes that by wrapping
         * [ConsensusBinding.transport] in a routing decorator (a `RoutedRaftTransport`) that relays
         * over [ConsensusBinding.relayChannel] along the bounded path
         * `player → server → core → server → player`, preserving the true Raft origin end-to-end.
         *
         * A peer whose id is in [core] is wrapped as a **server** relay endpoint (it may take one
         * core hop, choosing it from [attachment]); any other peer is wrapped as a **player** relay
         * endpoint (it always forwards to its single server and never routes for anyone else). The
         * player branch never consults [attachment] — a federated player therefore passes
         * `attachment = { null }` (it owns no directory), and only the servers pass a live lookup.
         *
         * @param core The [NodeId]s of the server core — every one of them votes in this game.
         *   Must be non-empty.
         * @param attachment The live `(player) -> the server it is behind` lookup a **server** uses
         *   to pick its one core hop for a remote player. **Required** — a defaulted lookup would
         *   silently disable cross-server delivery (optional ≠ tuning). A player (never in [core])
         *   never reads it; pass `{ null }`.
         */
        public fun federatedCore(
            core: Set<NodeId>,
            attachment: (NodeId) -> NodeId?,
        ): ConsensusPlacement = object : ConsensusPlacement {
            override val seating: AuthoritySeating = AuthoritySeating.CoreVoters(core)

            override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode =
                scope.raftNode(
                    coreMembership(binding.self, core),
                    binding.federatedTransport(core, scope, attachment),
                    binding.storage,
                    binding.raftConfig,
                    binding.identity,
                )
        }

        /**
         * Hand the session a node the caller already constructed.
         *
         * The bootstrap performs all of its usual wiring (mux channels, presence, admission,
         * app-channel envelope) against [node] instead of constructing its own — the seam that
         * lets a consumer run the full game stack against a test double (e.g. a `FakeRaftNode`
         * pinned to `Leader`) under pure virtual time. The caller retains lifecycle ownership
         * of [node]; [GameSession.close] still calls [RaftNode.close] on it as usual.
         *
         * Seating is [AuthoritySeating.SessionPeers]: the bootstrap's own membership machinery
         * (roster requires, admission loops) applies unchanged, now driving the provided node.
         */
        public fun preBuilt(node: RaftNode): ConsensusPlacement = object : ConsensusPlacement {
            override val seating: AuthoritySeating = AuthoritySeating.SessionPeers

            override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode = node
        }
    }
}

/**
 * The initial membership a fixed-core placement seats: a [core] member starts as a voter over the
 * whole core; any other peer starts as a self-declared learner under that core, to be admitted into
 * the replicated config by the core leader's admission loop. Shared by [ConsensusPlacement.serverCore]
 * and [ConsensusPlacement.federatedCore] — the seating rule is identical; only the transport differs.
 */
private fun coreMembership(self: NodeId, core: Set<NodeId>): ClusterConfig =
    if (self in core) ClusterConfig(voters = core) else ClusterConfig(voters = core, learners = setOf(self))

/**
 * Wrap this binding's [ConsensusBinding.transport] in the cross-server routing decorator the
 * [ConsensusPlacement.federatedCore] placement uses: a **server** endpoint (this node's id is in
 * [core]) may take one core hop via [attachment]; a **player** endpoint (any other id) always
 * forwards to its single server. The relay rides [ConsensusBinding.relayChannel]; the decorator's
 * relay coroutine is parented by [scope].
 *
 * Internal so both [ConsensusPlacement.federatedCore] and its wiring test drive the identical
 * transport-selection logic.
 */
internal fun ConsensusBinding.federatedTransport(
    core: Set<NodeId>,
    scope: CoroutineScope,
    attachment: (NodeId) -> NodeId?,
): RaftTransport =
    if (self in core) {
        serverRelayTransport(transport, relayChannel, core, scope, attachment)
    } else {
        playerRelayTransport(transport, relayChannel, core, scope)
    }

/**
 * Backoff between core-admission attempts after a failed membership change, so a
 * transiently-stale leader (role flow still reads Leader while the engine already rejects
 * `changeMembership`) cannot hot-loop. Matches [changeMembershipWithRetry]'s internal cadence.
 */
private val CORE_ADMISSION_RETRY_BACKOFF = 200.milliseconds

/**
 * Core-side learner admission for the [ConsensusPlacement.serverCore] placement: whenever this
 * node is the core leader and a connected session peer is neither a core voter nor already an
 * admitted learner, commit a membership change adding it as a learner.
 *
 * Launched by [gameNode] on every core member; the role gate inside the [combine] ensures only
 * the current leader acts, so leadership moving between core nodes hands the loop over
 * automatically. Runs for the life of the bootstrap caller's scope.
 *
 * The admission domain is [seam]'s roster — the loop admits exactly the peers the seam can see.
 * Under the game-per-room composition ([gameNodeRoom]) that seam is one game's room, so each
 * game admits only its own players: per-game admission falls out of the room's structural
 * isolation rather than any bookkeeping here.
 *
 * A failed membership change (leadership moved between observation and call, or the bounded
 * retry gave up) is tolerated and re-attempted after [CORE_ADMISSION_RETRY_BACKOFF] — the
 * surviving leader's own loop takes over, and a genuinely stuck cluster still surfaces as the
 * session making no progress rather than a crashed bootstrap scope.
 */
internal fun CoroutineScope.launchCoreLearnerAdmission(
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
