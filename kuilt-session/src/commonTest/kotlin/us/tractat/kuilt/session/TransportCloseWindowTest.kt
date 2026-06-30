@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
