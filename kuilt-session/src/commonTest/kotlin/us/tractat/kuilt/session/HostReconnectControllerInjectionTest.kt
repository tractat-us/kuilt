@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
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

        override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
            ResumeResult.WindowNotYetOpen

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    @Test
    fun `host drives the injected reconnect controller on joiner transport close`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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

            // The room announces its own HeartbeatConfig-derived estimate inline the moment it
            // detects the drop (#1724 / #1618 Drop B), so the injected controller's authoritative
            // deadline is the one it *settles* on, not the first one announced.
            //
            // Collected as an ordered LIST, not `first { == SENTINEL }`: that predicate tolerates any
            // number of events before and after the one it matches, so a regression that dropped the
            // inline estimate entirely, or announced repeatedly, still satisfied it. The exact
            // sequence is the assertion — 500 ms (clock 0 + the config's reconnectWindow) then the
            // sentinel, estimate strictly before authority, nothing after.
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            val sawSentinel = CompletableDeferred<Unit>()
            backgroundScope.launch {
                hostRoom.events
                    .filterIsInstance<MembershipEvent.WindowOpened>()
                    .collect { event ->
                        windows += event
                        if (event.expiresAt == Instant.fromEpochMilliseconds(SENTINEL_EXPIRES_AT)) {
                            sawSentinel.complete(Unit)
                        }
                    }
            }
            testScheduler.runCurrent()

            // Transport close — the in-memory analog of a socket close.
            clientMesh.close()

            // The room routed the drop to the INJECTED controller, not a freshly-built default.
            assertEquals(PeerId("client"), spy.unresponsivePeer.await())

            // And the room surfaced the injected controller's window — a sentinel expiry the default
            // fixed-window controller (which would compute the 500 ms reconnectWindow) never emits.
            sawSentinel.await()
            testScheduler.runCurrent()

            // The roster must not contradict the announcement: a custom hold policy owns the window,
            // so its deadline supersedes the room's estimate on the level too.
            val level = assertIs<Liveness.Partitioned>(
                hostRoom.roster.value.first { it.id == PeerId("client") }.liveness,
            )
            assertAll(
                {
                    assertEquals(
                        listOf(
                            Instant.fromEpochMilliseconds(fastConfig.reconnectWindow.inWholeMilliseconds),
                            Instant.fromEpochMilliseconds(SENTINEL_EXPIRES_AT),
                        ),
                        windows.map { it.expiresAt },
                        "the room must announce its inline estimate first and the injected policy's " +
                            "authoritative deadline second, once each — observed $windows",
                    )
                },
                {
                    assertEquals(
                        listOf(PeerId("client"), PeerId("client")),
                        windows.map { it.peerId },
                        "both announcements name the dropped joiner",
                    )
                },
                {
                    assertEquals(
                        Instant.fromEpochMilliseconds(SENTINEL_EXPIRES_AT),
                        level.windowExpiresAt,
                        "the injected controller's deadline must reach the roster level, not just the event",
                    )
                },
            )
        }

    private companion object {
        // A window expiry no default controller could produce for this config: far from the 500 ms
        // (from clock 0) the DefaultJoinerReconnectController would compute.
        const val SENTINEL_EXPIRES_AT = 123_456L
    }
}
