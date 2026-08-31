package us.tractat.kuilt.cluster

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.acceptPump
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Assemble the complete-graph inter-server voter mesh — **transport-agnostic**. This is the body a
 * real fabric wraps: `voterMeshOverWebSockets` (JVM/Android) supplies WebSocket accept-sources and a
 * WebSocket [dial]; a deterministic in-memory harness can supply loopback ones. Everything that is
 * *not* WebSocket-specific — the K_M formation rule, the persistent accept-pumps, the redial
 * supervisors, the formation-timeout teardown, and the hand-off to [voterMeshOverSeams] — lives here.
 *
 * ## Mesh formation — canonical dial rule
 *
 * The M servers form a K_M complete graph where **each pair connects exactly once** (a double-dial
 * would waste a socket and force the mesh's dedup lottery to arbitrate). The rule is purely positional
 * and needs no coordination: order the voters by [NodeId], and **the lower id dials the higher**. So
 * the voter ranked `i` (0-based) dials the `M-1-i` voters above it and accepts exactly `i` inbound
 * links from the voters below it. For every pair the lower id is the sole dialer and the higher id the
 * sole acceptor — one connection, deterministically.
 *
 * Each server's [Mesh] starts **empty** and grows by [Mesh.addLink]: the dialer adds the connection it
 * opened, the acceptor adds the one it accepted, and both ends run [Mesh.addLink] concurrently.
 * Building empty-then-`addLink` (rather than handing dialed connections to the mesh at construction)
 * is what keeps formation deadlock-free — no server's mesh construction blocks on a handshake that
 * another server can only service once *its own* construction has returned.
 *
 * Once every link is up, the seams are handed to [voterMeshOverSeams], which wraps each in a
 * `SeamRaftTransport` and starts the voter [us.tractat.kuilt.raft.RaftNode]s.
 *
 * ## Reconnection — a dropped inter-server link heals
 *
 * A voter-to-voter link can drop at any time (a peer restarts, the network blips, a half-open TCP
 * corpse is reaped by the fabric ping). Three pieces keep the K_M mesh whole for the life of the
 * [VoterMesh]:
 *
 * - **A persistent accept-pump per voter, running from t0.** Each voter's inbound route is drained
 *   forever by [acceptPump] (not just the `index` links formation expects), so a peer that re-dials
 *   after a drop is admitted exactly as an initial joiner was.
 * - **A per-voter redial supervisor.** [superviseVoterReconnection] watches each voter's `peers` and,
 *   whenever a peer this voter is the designated dialer for (the lower-id-dials-higher rule) goes
 *   absent, re-dials it — via [dial] — under [ExponentialBackoff] full jitter until it returns, then
 *   falls idle.
 * - **A `hubMesh` per voter** (never terminal on drain), so losing a link removes only that peer and
 *   the seam keeps serving the rest while the supervisor re-dials.
 *
 * Both run on the mesh lifecycle scope built up front here (so they can start before formation) and
 * handed to [voterMeshOverSeams]; [VoterMesh.close] cancels pumps + supervisors + nodes together.
 *
 * ## Lifecycle
 *
 * Pumps, supervisors, and voter nodes all run on the mesh lifecycle scope (a child of the receiver);
 * [VoterMesh.close] cancels it and stops them all — and then, because this path **owns** the per-voter
 * `hubMesh` seams (`ownsSeams = true`), it gracefully closes each seam too. The seams run on their own
 * `SupervisorJob` scopes, so cancelling the mesh scope alone would NOT close them: without the graceful
 * close the inter-server sessions would stay ESTABLISHED and still answer pings, and peers would hold
 * this voter in-roster as a **zombie** indefinitely. Any transport resource shared by [dial]/[sourceOf]
 * (e.g. a Ktor `HttpClient`) is **not** closed here — the caller owns it.
 *
 * @param voters The M voter [NodeId]s. At least 2; an odd count is recommended for clean quorum. The
 *   list is sorted by [NodeId] internally, so caller order does not matter.
 * @param sourceOf Where each voter's inbound server-to-server links arrive — its [ConnectionSource].
 * @param dial Opens one outbound link: given the dialing voter and the target peer, returns a live
 *   [Connection]. Both the initial formation dial and every redial route through this. The transport
 *   (WebSocket, loopback, …) and any per-peer address mapping live entirely on the caller's side.
 * @param dispatcher Scheduler for each mesh's per-link read loops (scheduling only — the mesh guards
 *   its own state with primitives). Production passes `Dispatchers.Default`; tests pass a dispatcher
 *   derived from the test scheduler.
 * @param raftConfig Raft timing and election RNG. **Required** — seed [RaftConfig.random].
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 * @param random Source of the per-connection mesh nonces and per-voter reconnect backoff jitter. Each
 *   voter is given its own seeded child [Random] for each role (drawn sequentially before any
 *   concurrent formation) so the mesh's dedup tiebreak and the redial jitter are deterministic *and*
 *   no [Random] instance is shared across concurrent coroutines.
 * @param handshakeTimeout Ceiling on a single accepted link's handshake (see [acceptPump]). A conn that
 *   connects but never completes its `MeshHello` exchange is abandoned after this, so it cannot wedge
 *   the persistent accept-pump.
 * @param dialTimeout Ceiling on a single **redial** negotiation (see [superviseVoterReconnection]). A
 *   redial is fired the instant a peer drops, which routinely coincides with the peer still being
 *   unreachable in a byte-dropping way; an unbounded dial would hang forever and wedge the single-flight
 *   redial loop. This bounds every redial so a hung negotiation is abandoned and the backoff loop retries.
 * @param formationTimeout Hard bound on initial mesh formation — the initial dials plus awaiting the
 *   full K_M roster on every voter. A stalled handshake or a crashed voter fails formation fast rather
 *   than hanging — and on that failure this function tears down everything it started (cancels the
 *   accept-pumps, closes the partially-formed seams, and closes the dials abandoned mid-handshake, which
 *   no seam ever published and so cannot reach) before rethrowing, since the caller receives no
 *   [VoterMesh] handle to close. (Post-formation reconnection is unbounded by design.)
 * @param backoffBase Base delay for the reconnect backoff (full jitter, per [ExponentialBackoff]).
 * @param backoffCap Cap on the reconnect backoff — a long-partitioned peer is re-dialed at most this often.
 */
@Suppress("LongParameterList")
internal suspend fun CoroutineScope.assembleVoterMesh(
    voters: List<NodeId>,
    sourceOf: (NodeId) -> ConnectionSource,
    dial: suspend (dialer: NodeId, target: PeerId) -> Connection,
    dispatcher: CoroutineContext,
    raftConfig: RaftConfig,
    random: Random,
    handshakeTimeout: Duration,
    dialTimeout: Duration,
    formationTimeout: Duration,
    backoffBase: Duration,
    backoffCap: Duration,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): VoterMesh {
    require(voters.size >= 2) { "assembleVoterMesh needs at least 2 voters, got ${voters.size}" }
    val ordered = voters.sortedBy { it.value }

    // Draw child Randoms per voter up front (single-threaded) so nothing is shared across the
    // concurrent handshakes / redial loops below — a seeded Random is not thread-safe. The mesh
    // nonce source and the backoff jitter source are DISTINCT instances per voter: they are driven
    // concurrently (the mesh draws a nonce on every addLink — including redials — while the
    // supervisor draws jitter between redials), so they must not share one non-thread-safe Random.
    val voterRandom = ordered.associateWith { Random(random.nextLong()) }
    val backoffRandom = ordered.associateWith { Random(random.nextLong()) }

    // Every voter's mesh starts empty; links are added from both ends via addLink (see kdoc).
    val meshes: Map<NodeId, Mesh> = ordered.associateWith { voter ->
        hubMesh(
            selfId = PeerId(voter.value),
            connections = emptyList(),
            dispatcher = dispatcher,
            random = voterRandom.getValue(voter),
        )
    }

    // Build the mesh lifecycle scope UP FRONT — the persistent accept-pumps must run from t0 (before
    // formation), and the supervisors and voter nodes join it later. VoterMesh.close cancels it, so
    // pumps + supervisors + nodes all stop together. (It could not be VoterMesh.scope: that scope
    // does not exist until voterMeshOverSeams returns, after formation.)
    val meshScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    val fullPeerIdSet: Set<PeerId> = ordered.map { PeerId(it.value) }.toSet()

    // Formation dials this function has opened that no seam has taken ownership of YET. Joined
    // immediately after `dial` returns and left the instant `addLink` returns, so membership means
    // exactly "the MeshHello exchange is still running" — which on the failure path below means
    // "abandoned mid-handshake, and nothing but this function can close it" (#2587).
    //
    // Neither boundary can be skipped by a cancellation: no suspension point separates `dial`
    // returning from the join, or `addLink` returning from the leave, and a coroutine is only
    // cancellable at a suspension point. The dial coroutines run concurrently (one per voter, on a
    // production dispatcher), so the list is lock-guarded rather than resting on where they schedule.
    val unpublishedDialsLock = reentrantLock()
    val unpublishedDials = mutableListOf<Connection>()

    try {
        // (a) Persistent accept-pump per voter, from t0. Drains each voter's inbound route forever, so a
        // peer that re-dials after a drop is admitted just like an initial joiner — not merely the fixed
        // `index` links formation expects.
        ordered.forEach { voter ->
            meshScope.acceptPump(
                source = sourceOf(voter),
                handshakeTimeout = handshakeTimeout,
                onFailure = {},
                handle = { conn -> meshes.getValue(voter).addLink(conn) },
            )
        }

        // (b) Initial dials + await the full K_M roster, under a formation timeout. coroutineScope joins
        // every dial and roster-await before we build the nodes, so each voter's peer set is complete
        // before its RaftNode starts (synchronous formation). containsAll (over ==) is robust to a stray
        // non-voter conn on the route.
        withTimeout(formationTimeout) {
            coroutineScope {
                ordered.forEachIndexed { index, voter ->
                    val mesh = meshes.getValue(voter)
                    // Dial every higher-ranked voter once (lower id dials higher).
                    launch {
                        ordered.drop(index + 1).forEach { higher ->
                            val conn = dial(voter, PeerId(higher.value))
                            unpublishedDialsLock.withLock { unpublishedDials += conn }
                            mesh.addLink(conn)
                            // addLink returned, so the mesh has taken the conn off our hands on every
                            // arm it has: it published the link, registered it as draining, or already
                            // closed it itself. Deregister BY IDENTITY — a Connection is free to define
                            // equals, and two distinct links must never be conflated into one entry.
                            unpublishedDialsLock.withLock {
                                val at = unpublishedDials.indexOfFirst { it === conn }
                                if (at >= 0) unpublishedDials.removeAt(at)
                            }
                        }
                    }
                    launch { mesh.peers.first { it.containsAll(fullPeerIdSet) } }
                }
            }
        }

        // (c) Per-voter redial supervisor on meshScope. Started AFTER formation, when every dial target is
        // already present, so the loops sit idle until a real drop. Each voter re-dials only the peers it
        // is the designated dialer for (the higher-ranked ones), so no pair is ever double-dialed.
        ordered.forEachIndexed { index, voter ->
            val higher = ordered.drop(index + 1).map { PeerId(it.value) }.toSet()
            meshScope.superviseVoterReconnection(
                mesh = meshes.getValue(voter),
                dialTargets = higher,
                dial = { peer -> dial(voter, peer) },
                backoff = ExponentialBackoff(
                    base = backoffBase,
                    cap = backoffCap,
                    random = backoffRandom.getValue(voter),
                ),
                dialTimeout = dialTimeout,
            )
        }

        // (d) Hand meshScope to voterMeshOverSeams so close() cancels pumps + supervisors + nodes together.
        // ownsSeams = true: the hubMesh seams were created HERE, so VoterMesh.close must gracefully close
        // them (their SupervisorJob scopes are not under meshScope) — otherwise cancelling meshScope leaves
        // the inter-server sessions ESTABLISHED and peers hold this voter as a zombie forever.
        return voterMeshOverSeams(
            voterSeams = meshes,
            raftConfig = raftConfig,
            meshScope = meshScope,
            storageFactory = storageFactory,
            ownsSeams = true,
        )
    } catch (e: Throwable) {
        // Formation failed (e.g. formationTimeout fired on a stalled/crashed voter) — the caller never
        // received a VoterMesh, so it has NO handle to close the mesh scope. Tear down everything this
        // function started before rethrowing, or the accept-pumps drain forever and the partially-formed
        // seams (each on its own SupervisorJob scope, NOT a child of meshScope) linger with their live
        // sessions.
        meshScope.cancel()                       // stop the persistent accept-pumps + any supervisors
        // Close the internally-created hubMesh seams: their SupervisorJob scopes are not under meshScope,
        // so cancelling meshScope does not close them. Uncancellable so a TimeoutCancellationException
        // context does not skip the cleanup; best-effort per seam.
        withContext(NonCancellable) {
            // Per-seam `try`/`catch (Throwable)`, NOT `runCatchingCancellable`. The shield is here because
            // the failure being handled may itself be a `TimeoutCancellationException` — but inside it this
            // block's Job is parented to [NonCancellable], so a `CancellationException` arriving here can
            // only be one the seam's own `close` minted. `runCatchingCancellable` rethrows that case and
            // skips every remaining seam, which is the same skipped-cleanup this shield was written to
            // prevent, one level in (#1803).
            meshes.values.forEach {
                try {
                    it.close()
                } catch (_: Throwable) {
                    // Best-effort: one seam refusing to close must not strand its siblings open.
                }
            }
            // And the dials no seam ever learned about (#2587). The loop above closes every link a seam
            // PUBLISHED; a dial still exchanging its MeshHello when the timeout fired never got that far
            // — `addLink` suspends inside the handshake and the cancellation propagates out of it — so
            // the seam close provably cannot reach it, and neither can `meshScope.cancel()`: the conn
            // belongs to the caller's transport, not to any scope this function owns. Left open it is a
            // live session per stalled dial (over WebSockets, one held until the caller-owned HttpClient
            // is closed, with the peer seeing an ESTABLISHED session from a voter that has given up) —
            // the same zombie shape the seam close prevents, one layer down. A stalled dial is not an
            // edge case: it is what a crashed or slow voter produces, i.e. the ordinary formation
            // timeout.
            //
            // ONLY the abandoned ones. A conn leaves the register the instant `addLink` returns, so a
            // published link is never in it. That boundary is the point, not an optimisation: closing a
            // conn the mesh owns would close it a second time behind the back of the seam that owns its
            // lifecycle — an over-reach worse than the leak, and one no close COUNT can tell apart from
            // this fix (VoterMeshFormationTimeoutTest splits them by identity for exactly that reason).
            //
            // Read under the lock, closed outside it: `close` suspends, and a suspend call inside a lock
            // is the repo's standing no. Per-conn `try`/`catch (Throwable)` for the same reason the seam
            // loop above has one — inside this shield a CancellationException can only be one the conn's
            // own `close` minted, and rethrowing it would skip every remaining conn (#1803, #1824).
            unpublishedDialsLock.withLock { unpublishedDials.toList() }.forEach {
                try {
                    it.close()
                } catch (_: Throwable) {
                    // Best-effort, per conn: one refusing to close must not strand its siblings open.
                }
            }
        }
        throw e
    }
}
