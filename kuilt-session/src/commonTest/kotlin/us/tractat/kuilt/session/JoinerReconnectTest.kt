@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FlakyLifecycleLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Acceptance tests for #1037 — the **joiner** auto-resumes over a resumable base
 * (a [MuxClientLoom] whose torn base re-weaves on the next `join`) within the
 * reconnect window, instead of going straight to terminal [MembershipEvent.HostLost].
 *
 * Harness: a [MuxServerLoom] hub the host rooms over, and a [MuxClientLoom] the joiner
 * rooms over. `muxClient.closeBase()` is the in-memory analog of the client's socket
 * dropping. The joiner [SeamRoom] is built with a `reweave = { muxClient.join(tag) }`
 * lambda — the missing capability that lets it trigger the base re-weave.
 *
 * Timing (fast config): interval 100 ms, timeout 200 ms, reconnect window 500 ms.
 */
class JoinerReconnectTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    private val nameOf: (Rendezvous) -> String = { rv ->
        when (rv) {
            is Rendezvous.New -> rv.pattern.displayName
            is Rendezvous.Existing -> rv.tag.displayName
        }
    }

    @Test
    fun `joiner auto-resumes over a resumable base within the reconnect window`() =
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

            val clientId = PeerId("client")
            var seed = 1
            val base = object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    val (serverConn, clientConn) = connectionPair()
                    source.offer(serverConn)
                    return meshSeam(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
                }
            }
            val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
            val tag = InMemoryTag("table-7")
            val joinerRoom = SeamRoom(
                seam = muxClient.join(tag),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { muxClient.join(tag) },
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(joinerRoom.resumeToken, "joiner must hold a resume token after admit")

            val joinerPartitioned = async { joinerRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first() }
            val joinerWindowOpened = async { joinerRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first() }
            val joinerResumed = async { joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
            val hostResumed = async { hostRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
            val joinerHostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // Tear the base out from under the joiner (socket drop analog).
            muxClient.closeBase()

            // Advance WITHIN the 500 ms reconnect window; auto-resume must complete.
            repeat(4) { advanceTimeBy(100L) }

            assertIs<MembershipEvent.Partitioned>(joinerPartitioned.await())
            assertIs<MembershipEvent.WindowOpened>(joinerWindowOpened.await())
            assertIs<MembershipEvent.Resumed>(joinerResumed.await())
            assertIs<MembershipEvent.Resumed>(hostResumed.await())
            assertFalse(
                joinerHostLost.isCompleted,
                "joiner must not fall to HostLost after a successful in-window resume",
            )
            joinerHostLost.cancel()
        }

    @Test
    fun `joiner falls to HostLost when the base cannot re-weave within the window`() =
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
            SeamRoom(
                seam = hostSeam,
                role = SessionRole.Host,
                displayName = "table-7",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
            ).also { it.start() }

            val clientId = PeerId("client")
            var seed = 1
            // Base weaves once (admit) then fails every re-weave — the window can never heal.
            val base = object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    if (seed > 1) throw RuntimeException("base weave fails on reconnect")
                    val (serverConn, clientConn) = connectionPair()
                    source.offer(serverConn)
                    return meshSeam(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
                }
            }
            val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
            val tag = InMemoryTag("table-7")
            val joinerRoom = SeamRoom(
                seam = muxClient.join(tag),
                role = SessionRole.Joiner,
                displayName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { muxClient.join(tag) },
            ).also { it.start() }

            joinerRoom.roster.first { it.isNotEmpty() }
            val token = assertNotNull(joinerRoom.resumeToken)

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            muxClient.closeBase()

            // Advance PAST the 500 ms window with margin.
            repeat(9) { clockMs += 100L; advanceTimeBy(100L) }

            assertIs<MembershipEvent.HostLost>(hostLost.await())

            // Terminal after HostLost — a post-hoc resume is rejected.
            assertIs<ResumeResult.WindowClosed>(joinerRoom.resume(token))
        }

    @Test
    fun `joiner torn before admit goes straight to HostLost`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

            // A joiner with no host to admit it: it never mints a resume token, so a tear must
            // go straight to terminal HostLost, never holding the reconnect window open.
            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val tag = InMemoryTag("joiner")
            val joinerSeam = loom.join(tag)
            val joinerRoom = SeamRoom(
                seam = joinerSeam,
                role = SessionRole.Joiner,
                displayName = "joiner",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { loom.join(tag) },
            ).also { it.start() }

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
            assertNull(joinerRoom.resumeToken, "joiner torn before admit has no resume token")

            // Tear the joiner's transport. HostLost must fire with NO time advancement — the
            // no-token path is immediate, it does not wait out the reconnect window.
            joinerSeam.tear()
            runCurrent()
            assertTrue(
                hostLost.isCompleted,
                "with no resume token the joiner must go HostLost immediately, not hold the window",
            )
            assertIs<MembershipEvent.HostLost>(hostLost.await())
        }
}
