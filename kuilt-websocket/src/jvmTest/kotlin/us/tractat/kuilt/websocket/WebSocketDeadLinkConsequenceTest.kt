package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real Ktor sessions, with the hub's dispatcher held on purpose while the client's keeps running
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.core.fabric.identified
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * What a closed WebSocket session does to the layers above [Connection], on real sessions: a real
 * Netty server behind [KtorConnectionSource], a real CIO client on the other end.
 *
 * ## Forcing the window
 *
 * The session's outgoing queue usually dies after the mesh has already dropped the link, so a send
 * rarely lands between the two. The hub tests hold the hub's only thread across the close. Ktor's
 * session processors do not run on it, so the session dies; the mesh's read loop does, so the link
 * stays listed. Every send below lands in that window, and each test asserts that it is open.
 *
 * The session itself is out of reach behind [KtorConnectionSource], so [awaitRefusal] finds the
 * moment by sending on the accepted [Connection] until it refuses a frame. The frames go nowhere: the
 * far end has already closed.
 */
class WebSocketDeadLinkConsequenceTest {

    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hubThread = HeldThread()
    private val rawSessions = Channel<DefaultWebSocketServerSession>(Channel.UNLIMITED)
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var source: RecordingSource
    private lateinit var httpClient: HttpClient
    private var port = 0

    // Started here rather than in `runBlocking`, whose receiver would bind `embeddedServer` to the
    // `CoroutineScope` overload and parent the server to the blocking job.
    @BeforeTest
    fun startServer() {
        server = embeddedServer(Netty, port = 0) {
            source = RecordingSource(KtorConnectionSource(this, HUB_PATH))
            routing {
                webSocket(RAW_PATH) {
                    rawSessions.send(this)
                    closeReason.await()
                }
            }
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        httpClient = HttpClient(CIO) { install(ClientWebSockets) }
    }

    @AfterTest
    fun stopServer() {
        hubThread.close()
        rigScope.cancel()
        httpClient.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    private fun advertisement(sessionName: String) =
        WebSocketAdvertisement(url = "ws://127.0.0.1:$port$HUB_PATH", serverPeerId = HUB, sessionName = sessionName)

    private fun spokeLoom() = KtorMeshClientLoom(
        httpClient = httpClient,
        dispatcher = Dispatchers.Default,
        selfPeerId = SPOKE,
        random = Random(2),
    )

    /** One spoke admitted to a mesh on the held thread, with both ends past the handshake. */
    private suspend fun meshWithOneSpoke(): Pair<Mesh, Seam> {
        val mesh = hubMesh(HUB, emptyList(), hubThread.dispatcher, Random(1))
        val adopted = rigScope.async { mesh.addLink(source.accept()) }
        val spoke = rigScope.async { spokeLoom().weave(Rendezvous.Existing(advertisement("spoke"))) }.await()
        adopted.await()
        mesh.peers.first { SPOKE in it }
        return mesh to spoke
    }

    /** Open the window: the session is dead and nothing on the hub's thread has been able to notice. */
    private suspend fun killTheSpokeUnnoticed(closeIt: suspend () -> Unit) {
        hubThread.hold()
        closeIt()
        awaitRefusal(source.lastAccepted())
    }

    /**
     * The fix's direct consequence. Held, so nothing but the send can have removed the link: the read
     * loop that would otherwise notice the close has not run.
     */
    @Test
    fun aMeshSendToALiveCallerEvictsTheDeadLinkItFound() = realTime {
        val (mesh, spoke) = meshWithOneSpoke()
        killTheSpokeUnnoticed { spoke.close() }
        assertTrue(SPOKE in mesh.peers.value, "rig: the window is not open, the link has already gone")

        val thrown = attempt { mesh.sendTo(SPOKE, PAYLOAD) }

        assertNull(thrown, "MeshSeam.sendTo reports a dead link by evicting it and returning normally")
        assertFalse(SPOKE in mesh.peers.value, "the failed send must evict the link, with the read loop still held")
    }

    /** As above for the other send, whose guard has the same shape. */
    @Test
    fun aMeshBroadcastToALiveCallerEvictsTheDeadLinkItFound() = realTime {
        val (mesh, spoke) = meshWithOneSpoke()
        killTheSpokeUnnoticed { spoke.close() }
        assertTrue(SPOKE in mesh.peers.value, "rig: the window is not open, the link has already gone")

        val thrown = attempt { mesh.broadcast(PAYLOAD) }

        assertNull(thrown, "MeshSeam.broadcast is best-effort and must not throw at a live caller")
        assertFalse(SPOKE in mesh.peers.value, "the failed send must evict the link, with the read loop still held")
    }

    private class HostedRoom(
        val loom: MuxServerLoom,
        val hubSeam: Seam,
        val room: Room,
        val closeTheMembersSocket: suspend () -> Unit,
    )

    /** A member joined to a room hosted over `MuxServerLoom` + `KtorConnectionSource`, as a real hub is. */
    private suspend fun roomWithOneMember(): HostedRoom {
        val hubScope = CoroutineScope(SupervisorJob() + hubThread.dispatcher)
        val loom = MuxServerLoom(source, hubScope, HUB, RoomAuthorizer.AllowAll, random = Random(1))
        val hubSeam = loom.host(Pattern(ROOM))
        // A slow pinger so a heartbeat cannot land in the window and do the eviction under test.
        val quietPinger = HeartbeatConfig(interval = 60.seconds, timeout = 1.seconds, reconnectWindow = 1.seconds)
        val room = SeamRoomFactory.systemClock(loom, rigScope, heartbeatConfig = quietPinger).host(Pattern(ROOM))

        val memberScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = CapturingLoom(spokeLoom())
        val mux = MuxClientLoom(
            base = base,
            baseRendezvous = Rendezvous.Existing(advertisement("member")),
            scope = memberScope,
            nameOf = { ROOM },
        )
        val member = memberScope.async {
            SeamRoomFactory.systemClock(mux, memberScope).join(advertisement("member"))
        }.await()
        room.roster.first { roster -> roster.any { it.id == SPOKE } }
        member.roster.first { roster -> roster.any { it.id == HUB } }
        return HostedRoom(loom, hubSeam, room) {
            // So the member's mux does not redial the socket this test is about to close.
            memberScope.cancel()
            base.woven.await().close()
        }
    }

    private suspend fun assertTheDeadMemberIsEvicted(hosted: HostedRoom) {
        hubThread.release()
        hosted.loom.connectedPeers.first { SPOKE !in it }
        hosted.hubSeam.peers.first { SPOKE !in it }
    }

    /** The reproducer: one peer's closed socket, one addressed send, and a caller nobody cancelled. */
    @Test
    fun aRoomSendToADeadMemberDoesNotCancelALiveCaller() = realTime {
        val hosted = roomWithOneMember()
        killTheSpokeUnnoticed(hosted.closeTheMembersSocket)
        assertTrue(SPOKE in hosted.loom.connectedPeers.value, "rig: the window is not open, the hub has already noticed")

        val thrown = attempt { hosted.room.sendTo(SPOKE, PAYLOAD) }

        assertNull(thrown, "Room.sendTo over a dead link must not throw at a live caller")
        assertTheDeadMemberIsEvicted(hosted)
    }

    /** The same through the room's fan-out, where a rethrow also skips the remaining members. */
    @Test
    fun aRoomBroadcastOverADeadMemberDoesNotCancelALiveCaller() = realTime {
        val hosted = roomWithOneMember()
        killTheSpokeUnnoticed(hosted.closeTheMembersSocket)
        assertTrue(SPOKE in hosted.loom.connectedPeers.value, "rig: the window is not open, the hub has already noticed")

        val thrown = attempt { hosted.room.broadcast(PAYLOAD) }

        assertNull(thrown, "Room.broadcast over a dead link must not throw at a live caller")
        assertTheDeadMemberIsEvicted(hosted)
    }

    /**
     * The client path. `LinkSeam.writeLoop` is the only thing left that can end a seam whose read loop
     * cannot run, so it has to reach its own teardown when `send` fails.
     *
     * The read loop is parked behind a full inbox: capacity one, nobody collecting, and frames still
     * queued below it. It will not see the close, because it will not reach it. A cancellation from
     * `send` would end the write loop silently and leave this seam `Woven` over a dead session for as
     * long as the consumer stays behind, with every later send queueing into an outbox nobody drains.
     */
    @Test
    fun aClientSeamWhoseReadLoopIsStuckTearsDownWhenItsWriteLoopFindsTheSessionDead() = realTime {
        val clientEnd = rigScope.async {
            httpClient.webSocketSession("ws://127.0.0.1:$port$RAW_PATH")
        }.await()
        val serverEnd = rawSessions.receive()
        val seam = identified(
            conn = WebSocketConnection(clientEnd),
            selfId = SPOKE,
            remoteId = HUB,
            dispatcher = Dispatchers.Default,
            policy = DeliveryPolicy(capacity = 1),
        )
        repeat(FRAMES_PAST_THE_INBOX) { serverEnd.send(Frame.Binary(fin = true, data = PAYLOAD)) }
        serverEnd.close()
        while (!clientEnd.outgoing.isClosedForSend) delay(POLL)
        assertFalse(seam.state.value is SeamState.Torn, "rig: the read loop reached the close, so it is not stuck")

        seam.broadcast(PAYLOAD)

        assertNotNull(
            withTimeoutOrNull(TEAR_BOUND) { seam.state.first { it is SeamState.Torn } },
            "the write loop found a dead session and the seam stayed Woven, so it ended without tearing down",
        )
    }

    /** A single thread that a test can stop, for as long as it likes, from running anything else. */
    private class HeldThread : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val held = CountDownLatch(1)
        val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

        fun hold() {
            val running = CountDownLatch(1)
            executor.execute {
                running.countDown()
                held.await()
            }
            running.await()
        }

        fun release() = held.countDown()

        override fun close() {
            release()
            executor.shutdownNow()
        }
    }

    /** Keeps the raw connection a composer was handed, which is the only handle on the session. */
    private class RecordingSource(private val delegate: ConnectionSource) : ConnectionSource {
        private val accepted = CompletableDeferred<Connection>()

        suspend fun lastAccepted(): Connection = accepted.await()

        override suspend fun accept(): Connection = delegate.accept().also { accepted.complete(it) }
    }

    /** Keeps the base seam a mux wove, so a test can close the member's socket from under it. */
    private class CapturingLoom(private val delegate: Loom) : Loom {
        val woven = CompletableDeferred<Seam>()

        override fun capability(): TransportCapability = delegate.capability()

        override suspend fun weave(rendezvous: Rendezvous): Seam =
            delegate.weave(rendezvous).also { woven.complete(it) }
    }

    private companion object {
        val HUB = PeerId("hub")
        val SPOKE = PeerId("spoke")
        const val ROOM = "table"
        const val HUB_PATH = "/hub"
        const val RAW_PATH = "/raw"
        const val FRAMES_PAST_THE_INBOX = 4
        val PAYLOAD = byteArrayOf(1, 2, 3)
        val POLL = 5.milliseconds
        val TEAR_BOUND = 10.seconds
        val TEST_BOUND = 45.seconds

        /** Real time, bounded, so a regression fails as an assertion rather than a task timeout. */
        fun realTime(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(TEST_BOUND) { body() } }

        /** What [block] threw at this caller, cancellations included, with this caller still live. */
        suspend fun attempt(block: suspend () -> Unit): Throwable? {
            val thrown = try {
                block()
                null
            } catch (failure: Throwable) {
                failure
            }
            currentCoroutineContext().ensureActive()
            return thrown
        }

        /** Send until the link refuses a frame, which is the moment the session's queue is dead. */
        suspend fun awaitRefusal(link: Connection) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (attempt { link.send(byteArrayOf()) } != null) return
                delay(POLL)
            }
        }
    }
}
