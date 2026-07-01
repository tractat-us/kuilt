@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TransportCloseWindowTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    @Test
    fun `host opens reconnect window on joiner transport close`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

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
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = meshSeam(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val partitioned = async { hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first() }
            val windowOpened = async { hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first() }

            // Transport close — the in-memory analog of a socket close.
            clientMesh.close()

            assertIs<MembershipEvent.Partitioned>(partitioned.await())
            assertIs<MembershipEvent.WindowOpened>(windowOpened.await())
        }

    @Test
    fun `host reconnect window honors heartbeatConfig reconnectWindow`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

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
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = meshSeam(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val windowOpened = async { hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first() }
            clientMesh.close()

            // Partition fires at clock 0; the window must expire at the CONFIGURED 500 ms, not the
            // controller's hardcoded 60 s default (regression guard: the host and joiner windows
            // must be the same configured duration).
            assertEquals(
                Instant.fromEpochMilliseconds(fastConfig.reconnectWindow.inWholeMilliseconds),
                windowOpened.await().expiresAt,
            )
        }

    @Test
    fun `joiner resumes within the window after transport close`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }

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
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val client = PeerId("client")
            val (serverConn1, clientConn1) = connectionPair()
            source.offer(serverConn1)
            val clientMesh1 = meshSeam(client, listOf(clientConn1), dispatcher, Random(1L))
            val clientMux1 = NamedMux(clientMesh1, backgroundScope)
            val joinerRoom1 = SeamRoom(
                seam = clientMux1.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom1.roster.first { it.isNotEmpty() }
            val token = joinerRoom1.resumeToken!!

            // Drop, then reconnect a fresh transport with the SAME PeerId.
            clientMesh1.close()
            hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()

            val (serverConn2, clientConn2) = connectionPair()
            source.offer(serverConn2)
            val clientMesh2 = meshSeam(client, listOf(clientConn2), dispatcher, Random(2L))
            val clientMux2 = NamedMux(clientMesh2, backgroundScope)
            val joinerRoom2 = SeamRoom(
                seam = clientMux2.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            val hostResumed = async { hostRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }

            joinerRoom2.roster.first { it.isNotEmpty() }
            val result = joinerRoom2.resume(token)

            assertIs<us.tractat.kuilt.session.partition.ResumeResult.Success>(result)
            assertIs<MembershipEvent.Resumed>(hostResumed.await())
        }

    @Test
    fun `reconnect does not re-emit Joined for an already-admitted peer`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }

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
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val client = PeerId("client")
            val (serverConn1, clientConn1) = connectionPair()
            source.offer(serverConn1)
            val clientMesh1 = meshSeam(client, listOf(clientConn1), dispatcher, Random(1L))
            val clientMux1 = NamedMux(clientMesh1, backgroundScope)
            val joinerRoom1 = SeamRoom(
                seam = clientMux1.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom1.roster.first { it.isNotEmpty() }
            val token = joinerRoom1.resumeToken!!

            // Collect ALL host events from this point through the whole reconnect flow.
            val hostEvents = mutableListOf<MembershipEvent>()
            backgroundScope.launch { hostRoom.events.collect { hostEvents.add(it) } }

            // Drop, then reconnect a fresh transport with the SAME PeerId.
            clientMesh1.close()
            hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()

            val (serverConn2, clientConn2) = connectionPair()
            source.offer(serverConn2)
            val clientMesh2 = meshSeam(client, listOf(clientConn2), dispatcher, Random(2L))
            val clientMux2 = NamedMux(clientMesh2, backgroundScope)
            val joinerRoom2 = SeamRoom(
                seam = clientMux2.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            // Fresh SeamRoom re-broadcasts Hello on Woven → host sees Hello from an
            // already-admitted PeerId → re-admit path.
            joinerRoom2.roster.first { it.isNotEmpty() }
            joinerRoom2.resume(token)

            // Let the re-admit / resume frames settle.
            repeat(4) { clockMs += 100L; advanceTimeBy(100L) }

            val joinedForClient = hostEvents.count {
                it is MembershipEvent.Joined && it.member.id == client
            }
            assertEquals(1, joinedForClient, "expected exactly one Joined for $client across reconnect")
            assertEquals(1, hostRoom.roster.value.size)
        }

    @Test
    fun `window expires to Left PartitionExpired when no resume`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }

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
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val (serverConn, clientConn) = connectionPair()
            source.offer(serverConn)
            val clientMesh = meshSeam(PeerId("client"), listOf(clientConn), dispatcher, Random(1L))
            val clientMux = NamedMux(clientMesh, backgroundScope)
            SeamRoom(
                seam = clientMux.channel("table-7"),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }

            val left = async {
                hostRoom.events.filterIsInstance<MembershipEvent.Left>().first()
            }

            clientMesh.close()
            hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()

            // Advance past the 500 ms reconnect window (with margin).
            repeat(8) { clockMs += 100L; advanceTimeBy(100L) }

            assertEquals(LeaveReason.PartitionExpired, left.await().reason)
        }
}
