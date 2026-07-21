@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A host application can substitute its own [JoinerReconnectController] hold policy — e.g. a
 * predicate/unbounded hold that keeps a seat while a durable rejoin record exists — instead of the
 * default fixed-window controller (#1614).
 */
class HostReconnectControllerInjectionTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    /**
     * Records that the room drove *this* controller (not a freshly-built default) and holds the
     * seat with a sentinel window expiry the default fixed-window controller could never emit.
     */
    private class SpyReconnectController : JoinerReconnectController {
        private val _events = MutableSharedFlow<JoinerReconnectEvent>(replay = 16, extraBufferCapacity = 16)
        override val events: SharedFlow<JoinerReconnectEvent> = _events.asSharedFlow()

        val unresponsivePeer = CompletableDeferred<PeerId>()

        override fun onPeerUnresponsive(peerId: PeerId, at: Long) {
            unresponsivePeer.complete(peerId)
            _events.tryEmit(JoinerReconnectEvent.WindowOpened(peerId, expiresAt = SENTINEL_EXPIRES_AT))
        }

        override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult =
            ResumeResult.WindowNotYetOpen

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    @Test
    fun `host drives the injected reconnect controller on joiner transport close`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val spy = SpyReconnectController()

            val source = InMemoryConnectionSource()
            val serverLoom = MuxServerLoom(
                source = source,
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(13L),
            )
            val hostSeam = serverLoom.host(Pattern("table-7"))
            val hostRoom = SeamRoom(
                seam = hostSeam,
                role = SessionRole.Host,
                memberName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
                reconnectControllerFactory = { _, _, _ -> spy },
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = hubMesh(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                memberName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val windowOpened = async { hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first() }

            // Transport close — the in-memory analog of a socket close.
            clientMesh.close()

            // The room routed the drop to the INJECTED controller, not a freshly-built default.
            assertEquals(PeerId("client"), spy.unresponsivePeer.await())

            // And the room surfaced the injected controller's window — a sentinel expiry the default
            // fixed-window controller (which would compute the 500 ms reconnectWindow) never emits.
            assertEquals(
                Instant.fromEpochMilliseconds(SENTINEL_EXPIRES_AT),
                windowOpened.await().expiresAt,
            )
        }

    private companion object {
        // A window expiry no default controller could produce for this config: far from the 500 ms
        // (from clock 0) the DefaultJoinerReconnectController would compute.
        const val SENTINEL_EXPIRES_AT = 123_456L
    }
}
