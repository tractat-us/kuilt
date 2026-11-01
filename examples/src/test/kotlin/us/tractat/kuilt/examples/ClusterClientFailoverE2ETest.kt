package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end **cross-relay failover** test for [ClusterClient] over **real Ktor WebSocket
 * sockets**, part of epic #485 (closes #544).
 *
 * ## What this proves
 *
 * Two relay endpoints ([KtorRoomHost] A and B, distinct `serverPeerId`s and paths) front
 * **one shared M=3 voter mesh** + one [us.tractat.kuilt.cluster.ServerCluster] /
 * `RaftRelayHub`. A production [clusterClient] with a pinned `selfPeerId`:
 *
 * 1. Connects to relay A → proposes → commits.
 * 2. Relay A is killed (its accept-loop scope is cancelled — its rooms tear; the **voter
 *    mesh and relay B keep running**).
 * 3. The client's reconnect loop rotates to relay B and re-admits **keeping the same
 *    `clientNodeId`**.
 * 4. A subsequent propose commits — on the **same Raft log** (the new entry's index is later
 *    than the pre-failover entry's, and both replicate to the voters).
 *
 * ## Why this needs the single-relay-peer addressing fix (#544)
 *
 * Leadership is **not** moved on failover: it stays on whichever voter the election picked
 * (a still-alive voter — only relay A's *host* died, not a voter). The client re-admitted on
 * relay B forwards proposals to the leader through relay B, even though relay B's
 * `serverPeerId` need not align with the leader voter. That works because the client's
 * player relay transport (over its swappable `ManagedSeam`) wraps every send as
 * `RaftRelay(dest = leader)` addressed to its single relay peer, and the shared
 * `RaftRelayHub` dest-routes it on to the named voter. Without that the post-failover
 * propose would address an absent peer and hang.
 *
 * ## Real-socket discipline
 *
 * [KtorRoomHost] and [us.tractat.kuilt.raft.RaftNode] use real wall-clock delays. Every outer
 * [withTimeout] is a backstop against a wedge, not a measure of how long the work takes: the
 * healthy runs below finish far inside them, and the 106% and 107% failures this test recorded
 * in CI were a proposal lost at leg 1, which no wider bound could have saved. Measured at ambient
 * load over 12 runs, not under synthetic saturation; the numbers sit beside each constant.
 */
class ClusterClientFailoverE2ETest {

    private val pathA = "/ws/failover-relay-a"
    private val pathB = "/ws/failover-relay-b"
    private val serverPeerIdA = PeerId("cluster-relay-a")
    private val serverPeerIdB = PeerId("cluster-relay-b")
    private val roomPattern = Pattern("failover-e2e-room")

    // M=3 voters. Two are "aligned" with a relay's serverPeerId; the third is unaligned.
    private val voterA = NodeId(serverPeerIdA.value)
    private val voterB = NodeId(serverPeerIdB.value)
    private val voterC = NodeId("cluster-voter-c")

    /** M=3 real-socket election timing — wide enough to converge on a cold CI runner. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(544),
    )

    /**
     * How long one try waits before proposing again. A retry period, not a ceiling: the first
     * leg-2 try was lost in 12 of 12 measured runs, so this sets how long leg 2 takes.
     */
    private val proposeRetryPeriod = 3.seconds

    /** Backstop for the first election. Measured 197–258 ms. */
    private val leaderBackstop = 20.seconds

    /** Backstop for leg 1, retries included. Measured 111–232 ms. */
    private val leg1Backstop = 20.seconds

    /** Backstop for leg 2, retries included. Measured 3003–3012 ms, always 2 tries. */
    private val leg2Backstop = 40.seconds

    /** Backstop for each voter to see its command. Measured 0–1 ms. */
    private val voterBackstop = 10.seconds

    // The order of these two serials is load-bearing. The voter below folds its stream through
    // ClientSessionTable, which refuses any serial at or below the high-water mark, starting from
    // 0. Leg 1 commits first, so its serial must be the lower one and above 0, or the table would
    // skip cmd2 as a duplicate.
    private val preFailoverRequestId = 1L
    private val failoverRequestId = 7777L

    /**
     * Proposes [command] under the pinned [requestId] until a try commits, within [backstop].
     *
     * A proposal sent while the client is between relays is lost, and the caller's contract is to
     * retry with the same `requestId`. A try ends when [proposeRetryPeriod] passes or when the
     * leader it reached has stepped down.
     *
     * This follows the contract as it stands and repairs no production defect. `propose` hanging
     * on a lost forward is tracked by #3037, and the leader step-down that starts the leg-1
     * sequence is tracked by #3038.
     *
     * Prints the try count, which lands in the test-results XML. It is not asserted: how many tries
     * a run needs depends on the reconnect race.
     */
    private suspend fun proposeUntilCommitted(
        client: ClusterClient,
        leg: String,
        command: ByteArray,
        requestId: Long,
        backstop: Duration,
    ): LogEntry = withTimeout(backstop) {
        var tries = 0
        var committed: LogEntry? = null
        while (committed == null) {
            tries++
            committed = try {
                withTimeoutOrNull(proposeRetryPeriod) { client.propose(command, requestId = requestId) }
            } catch (_: LeadershipLostException) {
                null
            }
        }
        println("$leg committed at index ${committed.index} after $tries tries")
        committed
    }

    @Test
    fun `client fails over to a surviving relay and keeps committing on the same log`() =
        failoverScenario(loseFirstProposal = false)

    /**
     * Forces leg 1's first proposal into a reconnect gap, which the scenario above reaches only
     * when its first admit fails. With a bare `propose` at leg 1 this reds at [leg1Backstop], 4 of 4
     * measured.
     *
     * Killing the relay stands in for the failed admit. It reproduces the lost forward and not the
     * leader step-down that caused it in CI, so it says nothing about the step-down itself.
     */
    @Test
    fun `client whose first proposal falls in a reconnect gap commits it by retrying`() =
        failoverScenario(loseFirstProposal = true)

    private fun failoverScenario(loseFirstProposal: Boolean) =
        testApplication {
            val clientNodeId = NodeId("cluster-client-failover")

            // Two relay front-ends on the same test application, distinct paths + identities.
            val hostA = KtorRoomHost(application, pathA, serverPeerIdA, roomPattern)
            val hostB = KtorRoomHost(application, pathB, serverPeerIdB, roomPattern)

            // Pin the loom's selfPeerId to clientNodeId so the client keeps a stable wire
            // identity across the failover reconnect (the #544 prerequisite).
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                selfPeerId = PeerId(clientNodeId.value),
            )
            val advA = WebSocketAdvertisement("ws://localhost$pathA", serverPeerIdA, "client")
            val advB = WebSocketAdvertisement("ws://localhost$pathB", serverPeerIdB, "client")

            // A voter's committed stream, to prove both commands replicate to the shared mesh.
            // A retry can commit a second copy under the same key: the leader only coalesces a
            // retry of a result it committed in its own tenure, so a retry racing the first copy,
            // or landing after a step-down, appends again. The table is what makes the two slots
            // below mean cmd1 and cmd2 rather than the first two copies.
            val voterSessions = ClientSessionTable()
            val voterCmd1 = CompletableDeferred<ByteArray>()
            val voterCmd2 = CompletableDeferred<ByteArray>()

            coroutineScope {
                // ── One shared voter mesh + cluster, two relay hosts ──────────────────
                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = hostA,
                    voterIds = listOf(voterA, voterB, voterC),
                    raftConfig = raftCfg,
                )

                // Observe one voter's committed log (voterC is unaligned with either relay).
                serverScope.launch {
                    var seen = 0
                    cluster.voterNodes.getValue(voterC).committed
                        .collect { committed ->
                            if (committed is Committed.Entry && voterSessions.shouldApply(committed.entry.dedupKey)) {
                                when (seen++) {
                                    0 -> voterCmd1.complete(committed.entry.command)
                                    1 -> voterCmd2.complete(committed.entry.command)
                                }
                            }
                        }
                }

                // Relay A in its own scope (killable independently); relay B in another.
                val relayScopeA = CoroutineScope(coroutineContext + Job())
                val relayScopeB = CoroutineScope(coroutineContext + Job())
                relayScopeA.launch { cluster.start() }          // mounts hostA
                relayScopeB.launch { cluster.runRelay(hostB) }  // mounts hostB

                // A leader must exist before admission's awaitLeader/changeMembership runs.
                withTimeout(leaderBackstop) { cluster.awaitLeader() }

                // ── Client: production extension over [advA, advB], round-robin from A ──
                val clientScope = CoroutineScope(coroutineContext + Job())
                val clusterConfig = ClusterConfig(
                    voters = setOf(voterA, voterB, voterC),
                    learners = setOf(clientNodeId),
                )
                val client: ClusterClient = clientScope.clusterClient(
                    loom = clientLoom,
                    clusterEndpoints = ClusterEndpoints(listOf(advA, advB)),
                    clientNodeId = clientNodeId,
                    clusterConfig = clusterConfig,
                    raftConfig = raftCfg,
                    clock = { Clock.System.now() },
                )

                if (loseFirstProposal) {
                    // A committed entry from the leader means the client knows who leads, so the
                    // proposal below is forwarded into the gap rather than queued for want of one.
                    withTimeout(leaderBackstop) { client.committed.first { it is Committed.Internal } }
                    relayScopeA.cancel()
                }

                // ── Leg 1: connect to relay A → propose → commit ──────────────────────
                val cmd1 = "action:before-failover".encodeToByteArray()
                val entry1 = proposeUntilCommitted(client, "leg-1", cmd1, preFailoverRequestId, leg1Backstop)
                assertContentEquals(cmd1, entry1.command, "leg-1 commit payload")

                // ── Kill relay A: cancel its scope → rooms tear → client seam tears ───
                relayScopeA.cancel()

                // ── Leg 2: client rotates to relay B, re-admits, proposes → commit ────
                // A propose issued during the reconnect gap forwards to a torn seam and is
                // lost (the engine does not auto-resend a forward it believed delivered).
                // The real-client contract is to RETRY with the same requestId until it
                // lands on the surviving relay; the pinned requestId makes it exactly-once
                // for a consumer folding ClientSessionTable, which skips any second copy. This
                // is the #532 "fresh-join + dedup intact" failover path.
                val cmd2 = "action:after-failover".encodeToByteArray()
                val entry2 = proposeUntilCommitted(client, "leg-2", cmd2, failoverRequestId, leg2Backstop)
                assertContentEquals(cmd2, entry2.command, "leg-2 commit payload")

                // Same shared log: leg 2 began only after entry1 committed, so a leader appends
                // cmd2 above it whichever copy entry1 names if leg 1 retried. The re-admit's config
                // entry sits between them too, so this is later, not next.
                assertTrue(
                    entry2.index > entry1.index,
                    "post-failover commit (${entry2.index}) extends the same log past ${entry1.index}",
                )

                // Both commands replicated to the shared voter mesh.
                assertContentEquals(cmd1, withTimeout(voterBackstop) { voterCmd1.await() }, "voter sees cmd1")
                assertContentEquals(cmd2, withTimeout(voterBackstop) { voterCmd2.await() }, "voter sees cmd2")

                // ── Teardown ──────────────────────────────────────────────────────────
                client.close()
                clientScope.cancel()
                cluster.close()
                serverScope.cancel()
                relayScopeB.cancel()
            }
        }
}
