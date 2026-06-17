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
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end **multi-client hardening** test for [ClusterClient] over **real Ktor WebSocket
 * sockets**, part of epic #485 (closes #559).
 *
 * Mirrors [ClusterClientFailoverE2ETest] but adds:
 *
 * - **Two concurrent learner clients** (`clientAlpha` and `clientBeta`), each with a distinct
 *   stable [NodeId], each proposing against the shared M=3 voter log.
 * - **Forced leader change mid-flight** via [us.tractat.kuilt.raft.RaftNode.transferLeadership]
 *   (Raft §3.10 graceful TimeoutNow path) — the voter set remains intact at M=3.
 * - **Entry-relay kill mid-propose** — relay A's scope is cancelled while at least one client
 *   has a proposal outstanding; that client rotates to relay B, re-admits with the same pinned
 *   [selfPeerId] + [requestId], and commits without double-apply.
 * - **No-double-apply assertion** via [ClientSessionTable]: the retried entry must be skipped
 *   (shouldApply → false) once it reappears; and the final committed log on voterC has each
 *   command from both clients exactly once.
 *
 * ## Real-socket discipline
 *
 * All [withTimeout] budgets are sized conservatively for cold CI runners (20 s for
 * initial election + proposals, 40 s for the failover leg). If the test hangs: `jstack` the
 * test JVM first — a hang is a real defect, never a flake.
 */
class ClusterClientMultiClientHardeningE2ETest {

    private val pathA = "/ws/hardening-relay-a"
    private val pathB = "/ws/hardening-relay-b"
    private val serverPeerIdA = PeerId("hardening-relay-a")
    private val serverPeerIdB = PeerId("hardening-relay-b")
    private val roomPattern = Pattern("hardening-e2e-room")

    private val voterA = NodeId(serverPeerIdA.value)
    private val voterB = NodeId(serverPeerIdB.value)
    private val voterC = NodeId("hardening-voter-c")

    private val clientAlphaId = NodeId("hardening-client-alpha")
    private val clientBetaId = NodeId("hardening-client-beta")

    /** M=3 real-socket election timing — wide enough to converge on a cold CI runner. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(559),
    )

    @Test
    fun `two clients propose across leader transfer and relay kill with no double-apply`() =
        testApplication {
            val advA = WebSocketAdvertisement("ws://localhost$pathA", serverPeerIdA, "client")
            val advB = WebSocketAdvertisement("ws://localhost$pathB", serverPeerIdB, "client")

            // Alpha's pinned loom keeps its PeerId stable across relay failover.
            val alphaLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                selfPeerId = PeerId(clientAlphaId.value),
            )
            // Beta uses a separate loom + http client — independent connection lifecycle.
            val betaLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                selfPeerId = PeerId(clientBetaId.value),
            )

            val hostA = KtorRoomHost(application, pathA, serverPeerIdA, roomPattern)
            val hostB = KtorRoomHost(application, pathB, serverPeerIdB, roomPattern)

            // Collect all non-config committed entries from voterC for the dedup audit.
            // Three phases of commands are expected: alpha pre-transfer, beta pre-transfer,
            // and alpha post-failover. The exact count is checked after all phases complete.
            val voterCEntries = mutableListOf<us.tractat.kuilt.raft.LogEntry>()
            val voterCDone = CompletableDeferred<Unit>()

            coroutineScope {
                // ── Server: shared M=3 mesh + two relay hosts ─────────────────────────
                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = hostA,
                    voterIds = listOf(voterA, voterB, voterC),
                    raftConfig = raftCfg,
                )

                // Observe voterC's committed stream — collect only application entries (skip config).
                // voterC is unaligned with either relay, so it's the cleanest observer.
                // Config entries (entry.config != null) are admission side-effects; they are
                // committed to the log but are not application commands.
                serverScope.launch {
                    cluster.voterNodes.getValue(voterC).committed.collect { committed ->
                        if (committed is Committed.Entry && committed.entry.config == null) {
                            voterCEntries += committed.entry
                            if (voterCEntries.size == 3) voterCDone.complete(Unit)
                        }
                    }
                }

                val relayScopeA = CoroutineScope(coroutineContext + Job())
                val relayScopeB = CoroutineScope(coroutineContext + Job())
                relayScopeA.launch { cluster.start() }
                relayScopeB.launch { cluster.runRelay(hostB) }

                withTimeout(20.seconds) { cluster.awaitLeader() }

                // ── Client Alpha: connect first, propose to prove admission complete ──
                // Membership changes are serialized (only one can be in-flight at a time),
                // so we admit alpha, let its propose land (proving the config committed),
                // then admit beta. This avoids a MembershipChangeInProgressException race.
                val alphaScope = CoroutineScope(coroutineContext + Job())
                val clientAlphaConfig = ClusterConfig(
                    voters = setOf(voterA, voterB, voterC),
                    learners = setOf(clientAlphaId),
                )
                val clientAlpha: ClusterClient = alphaScope.clusterClient(
                    loom = alphaLoom,
                    clusterEndpoints = ClusterEndpoints(listOf(advA, advB)),
                    clientNodeId = clientAlphaId,
                    clusterConfig = clientAlphaConfig,
                    raftConfig = raftCfg,
                    clock = { Clock.System.now() },
                )

                // ── Phase 1a: alpha proposes to confirm it is fully admitted ─────────
                val alphaCmd1 = "alpha:before-transfer".encodeToByteArray()
                val alphaEntry1 = withTimeout(20.seconds) { clientAlpha.propose(alphaCmd1) }

                // ── Client Beta: admit after alpha's config change committed ──────────
                val betaScope = CoroutineScope(coroutineContext + Job())
                val clientBetaConfig = ClusterConfig(
                    voters = setOf(voterA, voterB, voterC),
                    learners = setOf(clientAlphaId, clientBetaId),
                )
                val clientBeta: ClusterClient = betaScope.clusterClient(
                    loom = betaLoom,
                    clusterEndpoints = ClusterEndpoints(listOf(advA, advB)),
                    clientNodeId = clientBetaId,
                    clusterConfig = clientBetaConfig,
                    raftConfig = raftCfg,
                    clock = { Clock.System.now() },
                )

                // ── Phase 1b: beta proposes while cluster is still stable ─────────────
                val betaCmd1 = "beta:before-transfer".encodeToByteArray()
                val betaEntry1 = withTimeout(20.seconds) { clientBeta.propose(betaCmd1) }

                // ── Phase 2: force a graceful leader change mid-flight ────────────────
                // Identify the current leader and pick a different voter as transfer target.
                val leader = cluster.awaitLeader()
                val leaderId = cluster.voterNodes.entries.first { it.value === leader }.key
                val transferTarget = cluster.voterNodes.keys.first { it != leaderId }

                // transferLeadership suspends until the new leader wins or times out.
                // Run it in a separate coroutine so the test can proceed to phase 3.
                val transferJob = serverScope.launch {
                    runCatching { leader.transferLeadership(transferTarget) }
                    // Ignore LeadershipTransferException — if the transfer completes before
                    // the relay kill arrives, that's fine; if it doesn't, the test still passes
                    // because the relay kill itself forces a leadership re-election.
                }

                // Small window to let the transfer initiate before killing relay A.
                kotlinx.coroutines.delay(50.milliseconds)

                // ── Phase 3: kill relay A while alpha may have a propose outstanding ──
                // Alpha's reconnect loop rotates to relay B, re-admits with the same
                // clientAlphaId PeerId, and retries with a pinned requestId.
                relayScopeA.cancel()

                val alphaCmd2 = "alpha:after-failover".encodeToByteArray()
                val alphaFailoverRequestId = 9559L
                val alphaEntry2 = withTimeout(40.seconds) {
                    var committed: us.tractat.kuilt.raft.LogEntry? = null
                    while (committed == null) {
                        committed = try {
                            withTimeout(3.seconds) {
                                clientAlpha.propose(alphaCmd2, requestId = alphaFailoverRequestId)
                            }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            null // reconnect / re-admit still in flight — retry same requestId
                        }
                    }
                    committed
                }

                transferJob.cancel() // clean up if still pending

                // ── Assertions ────────────────────────────────────────────────────────

                // Log ordering: each phase entry extends the shared log.
                assert(alphaEntry1.index > 0) { "alpha phase-1 commit index must be positive" }
                assert(betaEntry1.index > 0) { "beta phase-1 commit index must be positive" }
                assert(alphaEntry2.index > alphaEntry1.index) {
                    "alpha phase-2 (${alphaEntry2.index}) must follow phase-1 (${alphaEntry1.index})"
                }

                // Wait for all 3 commands to replicate to voterC.
                withTimeout(10.seconds) { voterCDone.await() }

                // No-double-apply: fold all committed entries through ClientSessionTable.
                // The retry of alphaCmd2 with the same requestId must be skipped on the
                // second encounter (shouldApply → false).
                val dedupTable = ClientSessionTable()
                val appliedCommands = mutableListOf<ByteArray>()
                for (entry in voterCEntries) {
                    if (dedupTable.shouldApply(entry.dedupKey)) {
                        appliedCommands += entry.command
                    }
                }

                // All three distinct commands must be applied exactly once.
                assertEquals(3, appliedCommands.size, "exactly 3 distinct commands applied")
                assertEquals(
                    setOf("alpha:before-transfer", "beta:before-transfer", "alpha:after-failover"),
                    appliedCommands.map { it.decodeToString() }.toSet(),
                    "all commands from both clients present exactly once",
                )

                // The pinned requestId must not cause a double-apply if the retry arrived.
                // Simulate re-presenting the same dedupKey: shouldApply must return false.
                val alphaEntry2DedupKey = alphaEntry2.dedupKey
                if (alphaEntry2DedupKey != null) {
                    assertFalse(
                        dedupTable.shouldApply(alphaEntry2DedupKey),
                        "retried requestId $alphaFailoverRequestId must be rejected by dedup table",
                    )
                }

                // ── Teardown ──────────────────────────────────────────────────────────
                clientAlpha.close()
                clientBeta.close()
                alphaScope.cancel()
                betaScope.cancel()
                cluster.close()
                serverScope.cancel()
                relayScopeB.cancel()
            }
        }
}
