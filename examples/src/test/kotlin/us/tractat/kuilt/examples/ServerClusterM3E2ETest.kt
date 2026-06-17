package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.clusterClientWithNode
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration test for [us.tractat.kuilt.cluster.ServerCluster] with M=3 voters
 * over **real Ktor WebSocket sockets**, part of epic #485 (#545).
 *
 * ## Current status — blocked by production bug (#545)
 *
 * This test is a **diagnostic repro** for a production issue discovered while implementing
 * the M=3 E2E path. The test structure is correct; the failure reveals a `LearnerRouter`
 * bug that must be fixed before the assertion in `propose` can pass.
 *
 * ## Root cause: LearnerRouter fans Forward messages to all voters
 *
 * `LearnerRouter.addLearner` creates a relay coroutine that fans learner-incoming swatches
 * to ALL voter `MutableSharedFlow` inbounds:
 * ```
 * flows.forEach { flow -> flow.tryEmit(envelope) }
 * ```
 * When the learner's [RaftNode] sends a `Forward(command)` to the leader, this relay
 * delivers it to ALL 3 voter engines. The 2 non-leader followers immediately reply:
 * ```
 * send(from, RaftMessage.ForwardResponse(clientRequestId, ForwardOutcome.NotLeader))
 * ```
 * Because `LearnerRouter.sendToLearner` uses `seam.broadcast`, this `NotLeader` reply
 * arrives at the learner with `sender = serverPeerId = NodeId("cluster-server-m3")`. The
 * learner's `onForwardResponse` removes the pending forward and completes it exceptionally
 * with [LeadershipLostException] — racing and beating the leader's positive `Committed` reply.
 *
 * At M=1 this is invisible: the single voter is always the leader, so its `onForward`
 * succeeds. At M≥2, any follower reaching `onForward` before the leader's `Committed`
 * reply causes [ClusterClient.propose] to throw.
 *
 * ## Production fix needed
 *
 * `LearnerRouter` must route learner-incoming messages to the **leader voter's inbound only**,
 * not to all voter inbounds. An alternative is for `RaftEngine.onForward` on a follower to
 * silently drop (not reply to) Forwards from learners when another voter is the known leader —
 * but that changes Raft semantics. The recommended fix is in the router: route to leader.
 *
 * ## NodeId ↔ serverPeerId alignment at M=3
 *
 * One additional wrinkle exists (see brief for #545): the learner's `LinkSeam` stamps
 * `sender = serverPeerId` on every inbound frame, so the learner attributes ALL Raft traffic
 * to `NodeId(serverPeerId.value)`. Once `LearnerRouter` is fixed, the aligned-voter approach
 * (pin one voter's id to `serverPeerId`, transfer leadership to it before client joins) will
 * handle the alignment at M=3.
 *
 * ## Real-socket discipline
 *
 * [KtorRoomHost] and [us.tractat.kuilt.raft.RaftNode] use real wall-clock delays.
 * Every [withTimeout] here is a hard bound sized conservatively for cold CI runners.
 */
class ServerClusterM3E2ETest {

    private val serverPath = "/ws/cluster-m3-e2e"
    private val serverPeerId = PeerId("cluster-server-m3")
    private val roomPattern = Pattern("cluster-m3-e2e-room")

    /**
     * The "aligned" voter: its NodeId matches serverPeerId so the learner's SeamRaftTransport
     * correctly attributes inbound frames when this voter is the leader.
     */
    private val alignedVoterId = NodeId(serverPeerId.value)
    private val voterId2 = NodeId("cluster-voter-2")
    private val voterId3 = NodeId("cluster-voter-3")

    /**
     * Timing for real-socket M=3 elections.
     *
     * Wider election windows than M=1 tests (100–200ms vs 5–10ms) for two reasons:
     * 1. [us.tractat.kuilt.raft.RaftNode.transferLeadership] succeeds within one
     *    election-timeout window — 10ms is razor-thin on a loaded CI JVM.
     * 2. The M=3 voter mesh needs real network round-trips over loopback WebSocket for
     *    vote grants — these take a non-trivial fraction of the election window.
     * 200ms is still fast enough for the test to finish well under the 20s propose timeout.
     */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(545),
    )

    @Test
    fun `M=3 ServerCluster — propose commits and replicates to all three voters`() =
        testApplication {
            val clientCommittedPayload = CompletableDeferred<ByteArray>()

            // Per-voter deferreds to confirm replication on all three nodes.
            val voterCommits = mapOf(
                alignedVoterId to CompletableDeferred<ByteArray>(),
                voterId2 to CompletableDeferred<ByteArray>(),
                voterId3 to CompletableDeferred<ByteArray>(),
            )

            coroutineScope {
                // ── Server: M=3 ServerCluster ─────────────────────────────────────────
                val host = KtorRoomHost(
                    application = application,
                    path = serverPath,
                    serverPeerId = serverPeerId,
                    pattern = roomPattern,
                )

                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = host,
                    voterIds = listOf(alignedVoterId, voterId2, voterId3),
                    raftConfig = raftCfg,
                )

                // Collect committed entries on each voter before starting the relay loop.
                cluster.voterNodes.forEach { (voterId, node) ->
                    serverScope.launch {
                        node.committed
                            .first { it is Committed.Entry }
                            .let { committed ->
                                voterCommits.getValue(voterId).complete(
                                    (committed as Committed.Entry).entry.command,
                                )
                            }
                    }
                }

                serverScope.launch { cluster.start() }

                // ── Leadership alignment: ensure alignedVoterId leads before client joins
                //
                // The learner's LinkSeam stamps sender = serverPeerId on every inbound frame.
                // SeamRaftTransport maps sender → NodeId, so the learner attributes all
                // AppendEntries to NodeId(serverPeerId.value) = alignedVoterId.
                // If a different voter wins the initial election, transfer leadership to the
                // aligned voter before the client joins — purely a test arrangement, no
                // production change needed.
                //
                // transferLeadership can throw LeadershipLostException or NotLeaderException
                // if leadership changes between awaitLeader() and the transfer call (real-clock
                // M=3 elections are non-deterministic). Retry until the aligned voter is confirmed
                // leader, or until the outer timeout fires.
                withTimeout(20.seconds) {
                    pinLeadershipToAlignedVoter(cluster.voterNodes, alignedVoterId)
                }

                // ── Client: join → derive NodeId → RaftNode → ClusterClient ──────────
                val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
                val clientScope = CoroutineScope(coroutineContext + Job())

                val clientRoom = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScope)
                    .join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$serverPath",
                            serverPeerId = serverPeerId,
                            displayName = "cluster-client-m3",
                        ),
                    )
                val clientSeam = clientRoom.channel("raft")

                val clientNodeId = NodeId(clientSeam.selfId.value)
                val learnerConfig = ClusterConfig(
                    voters = setOf(alignedVoterId, voterId2, voterId3),
                    learners = setOf(clientNodeId),
                )

                withTimeout(5.seconds) { clientRoom.roster.first { it.isNotEmpty() } }

                val clientNode = clientScope.raftNode(
                    clusterConfig = learnerConfig,
                    transport = SeamRaftTransport(clientSeam),
                    storage = InMemoryRaftStorage(),
                    raftConfig = raftCfg,
                )

                val client: ClusterClient = clusterClientWithNode(clientNode)

                clientScope.launch {
                    client.committed
                        .first { it is Committed.Entry }
                        .let { committed ->
                            clientCommittedPayload.complete(
                                (committed as Committed.Entry).entry.command,
                            )
                        }
                }

                // ── Propose via ClusterClient ─────────────────────────────────────────
                val command = "action:m3-move=1".encodeToByteArray()
                withTimeout(20.seconds) { client.propose(command) }

                // ── Assertions: committed on client and on all 3 voters ───────────────
                val clientPayload = withTimeout(10.seconds) { clientCommittedPayload.await() }
                assertContentEquals(command, clientPayload, "client committed payload must match")

                voterCommits.forEach { (voterId, deferred) ->
                    val voterPayload = withTimeout(10.seconds) { deferred.await() }
                    assertContentEquals(
                        command,
                        voterPayload,
                        "voter $voterId committed payload must match",
                    )
                }

                // ── Teardown ──────────────────────────────────────────────────────────
                client.close()
                clientScope.cancel()
                cluster.close()
                serverScope.cancel()
            }
        }
}

/**
 * Ensures [alignedVoterId] is the leader before the client joins.
 *
 * Polls [voterNodes] for a current leader. If the current leader is not [alignedVoterId],
 * attempts [us.tractat.kuilt.raft.RaftNode.transferLeadership]. Retries on
 * [LeadershipLostException] or [NotLeaderException] — in a real-clock M=3 cluster, leadership
 * may flip between [us.tractat.kuilt.cluster.VoterMesh.awaitLeader] returning and the transfer
 * call executing.
 *
 * Returns when [alignedVoterId]'s [us.tractat.kuilt.raft.RaftNode.role] is [RaftRole.Leader].
 * Caller must bound this with [withTimeout].
 */
private suspend fun pinLeadershipToAlignedVoter(
    voterNodes: Map<NodeId, RaftNode>,
    alignedVoterId: NodeId,
) {
    val alignedNode = voterNodes.getValue(alignedVoterId)

    while (alignedNode.role.value !is RaftRole.Leader) {
        val currentLeader = voterNodes.values.firstOrNull { it.role.value is RaftRole.Leader }
        if (currentLeader == null) {
            // No leader yet — wait briefly and retry.
            delay(5)
            continue
        }
        try {
            currentLeader.transferLeadership(alignedVoterId)
        } catch (_: LeadershipLostException) {
            // Leader stepped down mid-transfer; retry with whoever leads next.
        } catch (_: NotLeaderException) {
            // Leadership changed between our check and the call; retry.
        }
    }
}
