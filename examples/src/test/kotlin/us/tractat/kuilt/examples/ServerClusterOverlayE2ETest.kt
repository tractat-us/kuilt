package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.cluster.overlayServer
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end proof of Task 2 of #794 slice 6 PR 2b: a [ServerCluster]
 * [ServerCluster.overlay] is populated **structurally** by the accept path — every
 * admitted connection discharges the two-tier overlay's attachment obligation via
 * `OverlayServer.admit`, and a disconnect retracts it via `OverlayServer.evict`.
 *
 * Drives the real admission path over Ktor WebSockets (like [ServerClusterE2ETest]),
 * real clock, hard [withTimeout] bounds — no virtual scheduler is in the loop.
 */
class ServerClusterOverlayE2ETest {

    private val serverPath = "/ws/overlay-e2e"
    private val serverPeerId = PeerId("overlay-e2e-server")
    private val roomPattern = Pattern("overlay-e2e-room")

    private val raftCfg = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        random = Random(7),
    )

    @Test
    fun `admitted connection populates the overlay directory and relay teardown retracts it`() =
        testApplication {
            // The single voter id must match the host serverPeerId so the default overlay's
            // self (= PeerId(voterIds.first())) equals serverPeerId — the value the directory
            // records for the admitted client.
            val voterId = NodeId(serverPeerId.value)

            val host = KtorRoomHost(
                application = application,
                path = serverPath,
                serverPeerId = serverPeerId,
                pattern = roomPattern,
            )
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            coroutineScope {
                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = host,
                    voterIds = listOf(voterId),
                    raftConfig = raftCfg,
                )
                // Run the relay accept loop on its own job so tearing it down (a relay
                // endpoint going away — the same trigger that fires LearnerRouter.removeLearner)
                // exercises the eviction path while the cluster's overlay lives on.
                val relayJob = serverScope.launch { cluster.start() }

                // ── Client joins ────────────────────────────────────────────────
                val clientScope = CoroutineScope(coroutineContext + Job())
                val clientRoom = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScope)
                    .join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$serverPath",
                            serverPeerId = serverPeerId,
                            sessionName = "overlay-client",
                        ),
                    )
                val clientSeam = clientRoom.channel("raft")
                withTimeout(5.seconds) { clientRoom.roster.first { it.isNotEmpty() } }

                // The overlay key is the client's PeerId as the server's roster sees it,
                // which equals the client's own Seam selfId.
                val clientId = clientSeam.selfId

                // admitLearner publishes overlay.admit BEFORE changeMembership, so the
                // attachment appears shortly after the handshake — poll with a hard bound.
                val attachedTo = withTimeout(10.seconds) {
                    var lookup = cluster.overlay.lookup(clientId)
                    while (lookup == null) {
                        delay(20.milliseconds)
                        lookup = cluster.overlay.lookup(clientId)
                    }
                    lookup
                }
                assertEquals(serverPeerId, attachedTo, "admit records client → server in the overlay directory")

                // Wait until the learner is fully admitted (membership committed) so
                // admitLearner has passed changeMembership and is parked in awaitCancellation —
                // only there does a teardown run its evict finally (a cancel mid-changeMembership
                // rethrows before it, exactly as it would skip LearnerRouter.removeLearner).
                val learnerId = NodeId(clientId.value)
                withTimeout(15.seconds) {
                    cluster.awaitLeader().membership.first { learnerId in it.learners }
                }
                delay(300.milliseconds)

                // ── Relay teardown ⇒ evict retracts the attachment ──────────────
                // admitLearner holds each admitted room via awaitCancellation until its
                // relay scope tears (exactly as LearnerRouter.removeLearner is driven), and
                // its finally runs overlay.evict. The cluster's overlay outlives the relay job.
                relayJob.cancel()
                clientScope.cancel()
                withTimeout(10.seconds) {
                    while (cluster.overlay.lookup(clientId) != null) {
                        delay(20.milliseconds)
                    }
                }
                assertNull(cluster.overlay.lookup(clientId), "relay teardown retracts the overlay attachment")

                cluster.close()
                serverScope.cancel()
            }
        }

    @Test
    fun `the federated overload threads the caller-supplied overlay`() =
        testApplication {
            val host = KtorRoomHost(
                application = application,
                path = "/ws/overlay-overload",
                serverPeerId = serverPeerId,
                pattern = Pattern("overlay-overload-room"),
            )

            coroutineScope {
                val serverScope = CoroutineScope(coroutineContext + Job())

                // A caller-owned federated overlay over its own inter-server seams.
                val coreLoom = InMemoryLoom()
                val coreSeam = coreLoom.host(Pattern("core"))
                coreLoom.join(InMemoryTag("core")) // a peer, so the core seam is genuinely federated
                val dirLoom = InMemoryLoom()
                val dirSeam = dirLoom.host(Pattern("dir"))
                dirLoom.join(InMemoryTag("dir"))
                val myOverlay = overlayServer(
                    self = serverPeerId,
                    coreSeam = coreSeam,
                    directorySeam = dirSeam,
                    scope = serverScope,
                    clock = { 0 },
                )

                val cluster = serverScope.serverCluster(
                    host = host,
                    voterIds = listOf(NodeId(serverPeerId.value)),
                    raftConfig = raftCfg,
                    overlay = myOverlay,
                )

                assertSame(myOverlay, cluster.overlay, "the overload exposes exactly the caller's overlay")

                cluster.close()
                myOverlay.close()
                serverScope.cancel()
            }
        }
}
