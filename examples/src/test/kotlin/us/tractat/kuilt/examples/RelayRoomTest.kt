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
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration example: a single-voter Raft node hosted behind [KtorRoomHost] (the
 * relay-room topology) with one learner client admitted via [ClusterConfig] add-learner.
 *
 * ## What this proves (S1b of epic #485)
 *
 * - [KtorRoomHost] acts as the relay: each accepted WebSocket connection becomes a
 *   [us.tractat.kuilt.session.Room] whose channel Seam backs the server's [RaftNode].
 * - A client connects via [KtorClientLoom] + [SeamRoomFactory], derives peer IDs from
 *   the admitted [us.tractat.kuilt.session.Room], and builds its own learner [RaftNode].
 * - The server (leader) calls [RaftNode.changeMembership] to add the client as a learner
 *   (a learner-set-only change — skips joint consensus, decision D3).
 * - The learner proposes a command: [RaftNode.propose] forwards it to the leader, which
 *   commits it and replicates it back through normal AppendEntries.
 * - Both the server and the learner observe the committed entry on their
 *   [RaftNode.committed] flows.
 *
 * ## Why real I/O, not virtual time
 *
 * [KtorRoomHost] uses real Ktor WebSocket sockets that cannot be driven by a virtual-time
 * scheduler. [RaftNode] also uses real-clock [kotlinx.coroutines.delay] for elections
 * (fast config: 5 ms / 10 ms / 2 ms). Virtual time is appropriate for unit tests using
 * [us.tractat.kuilt.raft.test.FakeRaftNode] or pure in-memory Looms; this test
 * exercises the relay path end-to-end with real network I/O.
 *
 * ## Admit-handshake and Room-lifetime ordering
 *
 * Two ordering constraints must both be satisfied:
 *
 * 1. **Admit-handshake:** The client's Room must complete its half of the handshake
 *    (receiving Welcome and admitting the server) before the client's RaftNode starts.
 *    `RoomChannelSeam.incoming` filters by `isAdmitted(sender)` — frames from an
 *    un-admitted peer are silently dropped. We wait on the roster [StateFlow] on both
 *    sides before creating either RaftNode.
 *
 * 2. **Room lifetime:** [KtorRoomHost] calls [LeaveReason.Normal] on the Room when
 *    the `onRoom` callback returns, which tears down the WebSocket. The server's
 *    `onRoom` block must therefore stay alive for the full duration of the test.
 *    We pass [serverCommittedPayload] into the `onRoom` block and await its completion
 *    there — keeping the connection open until both nodes have confirmed the commit.
 */
class RelayRoomTest {

    private val serverPath = "/ws/relay-raft"
    private val serverPeerId = PeerId("relay-server")
    private val roomPattern = Pattern("relay-raft-cluster")

    /** Fast timing so elections complete in a real-socket test without long waits. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        random = Random(42),
    )

    @Test
    fun `learner client joins relay room and observes proposal committed by leader`() =
        testApplication {
            // The server's NodeId is known upfront — KtorRoomHost uses a fixed PeerId.
            val serverId = NodeId(serverPeerId.value)

            val host = KtorRoomHost(
                application = application,
                path = serverPath,
                serverPeerId = serverPeerId,
                pattern = roomPattern,
            )

            // Carries the learner ClusterConfig from the server's onRoom callback to the
            // client coroutine. Completed only after changeMembership succeeds.
            val learnerConfigDeferred = CompletableDeferred<ClusterConfig>()

            // Payload deferred pair — each node resolves its own when it observes the commit.
            val serverCommittedPayload = CompletableDeferred<ByteArray>()
            val learnerCommittedPayload = CompletableDeferred<ByteArray>()

            // Closed once both sides have committed — lets the server's onRoom block exit
            // (returning from onRoom triggers room.leave() which tears down the WebSocket;
            // we must hold the room open until the learner has also replicated the entry).
            val bothCommitted = CompletableDeferred<Unit>()

            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
            coroutineScope {
                // Server accept loop: runs until the coroutine scope is cancelled.
                //
                // LIFECYCLE CONSTRAINT: the onRoom callback must NOT return until both the
                // server and the learner have observed the committed entry. Returning from
                // onRoom triggers room.leave() → WebSocket close → client's SeamRoom stops
                // receiving → learner never gets the AppendEntries that carries the commit.
                val serverJob = launch {
                    host.start { room ->
                        val serverSeam = room.channel("raft")

                        // Wait for the client to be admitted in the server's room roster
                        // before deriving the clientId. roster is a StateFlow — race-free.
                        val admittedPeer = withTimeout(5.seconds) {
                            room.roster.first { it.isNotEmpty() }.first()
                        }
                        val clientId = NodeId(admittedPeer.id.value)
                        val withLearner = ClusterConfig.withLearner(listOf(serverId), clientId)

                        val voterConfig = ClusterConfig.ofVoters(listOf(serverId))
                        val serverNode = this.raftNode(
                            voterConfig,
                            SeamRaftTransport(serverSeam),
                            InMemoryRaftStorage(),
                            raftCfg,
                        )

                        // Collect committed entries on the server side.
                        launch {
                            serverNode.committed
                                .first { it is Committed.Entry }
                                .let { committed ->
                                    serverCommittedPayload.complete(
                                        (committed as Committed.Entry).entry.command
                                    )
                                }
                        }

                        // Become leader, add the learner, signal client.
                        serverNode.awaitLeadership()
                        serverNode.changeMembership(withLearner)
                        learnerConfigDeferred.complete(withLearner)

                        // Hold the room open until both sides confirm the commit.
                        withTimeout(15.seconds) { bothCommitted.await() }
                    }
                }

                // A dedicated scope for the client room and its RaftNode. Uses a fresh Job so
                // cancellation does not propagate to the outer coroutineScope. Cancelling this
                // scope terminates room background coroutines (runMainLoop, runPeersWatcher, etc.)
                // and the node's actor loop, so the outer coroutineScope can complete cleanly.
                val clientScope = CoroutineScope(coroutineContext + Job())
                val clientRoom = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScope)
                    .join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$serverPath",
                            serverPeerId = serverPeerId,
                            displayName = "learner-client",
                        )
                    )
                val clientSeam = clientRoom.channel("raft")
                val clientId = NodeId(clientSeam.selfId.value)

                // Ensure the admit handshake is complete on the client side before starting
                // the learner RaftNode. isAdmitted(server) must be true when AppendEntries
                // arrive — otherwise RoomChannelSeam.incoming drops them silently.
                withTimeout(5.seconds) { clientRoom.roster.first { it.isNotEmpty() } }

                // Wait for the server to signal the learner config, then verify peer IDs.
                val learnerConfig = withTimeout(10.seconds) { learnerConfigDeferred.await() }
                assertEquals(clientId, learnerConfig.learners.first())

                val clientNode = clientScope.raftNode(
                    learnerConfig,
                    SeamRaftTransport(clientSeam),
                    InMemoryRaftStorage(),
                    raftCfg,
                )

                // Observe committed entries on the learner side.
                clientScope.launch {
                    clientNode.committed
                        .first { it is Committed.Entry }
                        .let { committed ->
                            learnerCommittedPayload.complete(
                                (committed as Committed.Entry).entry.command
                            )
                        }
                }

                // Propose from the learner — forwarded to the leader, committed, replicated back.
                val command = "action:move=1".encodeToByteArray()
                clientNode.propose(command)

                val serverPayload = withTimeout(10.seconds) { serverCommittedPayload.await() }
                val learnerPayload = withTimeout(10.seconds) { learnerCommittedPayload.await() }

                // Signal the server's onRoom block to release the room (keeps WebSocket alive
                // until the learner has replicated the entry).
                bothCommitted.complete(Unit)

                assertEquals(command.decodeToString(), serverPayload.decodeToString())
                assertEquals(command.decodeToString(), learnerPayload.decodeToString())

                // Tear down the client: close the node first (sends EngineCommand.Close),
                // then cancel the client scope which stops all room background coroutines.
                clientNode.close()
                clientScope.cancel()
                serverJob.cancel()
            }
        }
}
