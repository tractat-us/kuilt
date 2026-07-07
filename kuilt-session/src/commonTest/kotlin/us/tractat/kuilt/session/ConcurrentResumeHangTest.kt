@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Regression tests for #1280 — concurrent [Room.resume] calls must compose instead of
 * silently overwriting the single `pendingResume` reply slot.
 *
 * Two legitimate concurrency shapes:
 * 1. Two app-level `resume()` calls race (the [Room.resumeToken] KDoc invites app-driven
 *    retries). Pre-fix, the second call overwrote the first caller's deferred, so the first
 *    `await()` hung forever.
 * 2. An app-level `resume()` races the internal #1037 auto-reconnect (`runHostReconnect`
 *    also calls `resume()`). Pre-fix, the public call stole the internal attempt's slot:
 *    the host's ResumeAck resolved the public call as Success while the internal attempt
 *    never returned, so the reconnect window timed out into `markHostLost` — a terminal
 *    room right after a *successful* resume.
 *
 * Timing (fast config): interval 100 ms, timeout 200 ms, reconnect window 500 ms.
 */
class ConcurrentResumeHangTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    // ── Race 1: two concurrent public resume() calls ─────────────────────────

    /**
     * Two concurrent `resume()` calls with the same token: both must complete, and both
     * with [ResumeResult.Success] (they coalesce onto the single in-flight attempt).
     * Pre-fix the second call overwrote `pendingResume`, orphaning the first caller's
     * deferred — the first `await()` hung forever.
     */
    @Test
    fun `two concurrent resume calls both complete with the shared result`() = runTest(timeout = 30.seconds) {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val faultyHostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        val hostRoom = makeSeamRoom(faultyHostSeam, SessionRole.Host, "Alice", clock, RoomId("room-1280"))
        val faultyJoinerSeam = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(faultyJoinerSeam, SessionRole.Joiner, "Bob", clock)

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }
        val token = assertNotNull(joinerRoom.resumeToken, "joiner must hold a resume token after admit")

        // Partition both links past the heartbeat timeout, within the reconnect window.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
        faultyJoinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
        repeat(4) { clockMs += 100L; advanceTimeBy(100L) }

        // Heal, then race two resume() calls.
        faultyHostSeam.heal()
        faultyJoinerSeam.heal()
        advanceTimeBy(50L)

        val first = async { joinerRoom.resume(token) }
        val second = async { joinerRoom.resume(token) }
        advanceTimeBy(100L)

        val firstResult = withTimeoutOrNull(5.seconds) { first.await() }
        val secondResult = withTimeoutOrNull(5.seconds) { second.await() }
        assertAll(
            {
                assertNotNull(
                    firstResult,
                    "first resume() must complete — overwritten pendingResume hangs it forever",
                )
            },
            { assertNotNull(secondResult, "second resume() must complete") },
            { assertIs<ResumeResult.Success>(firstResult) },
            { assertIs<ResumeResult.Success>(secondResult) },
        )
    }

    // ── Race 2: public resume() vs the internal #1037 auto-reconnect ─────────

    /**
     * A public `resume()` landing while the internal auto-reconnect's own `resume()` is
     * awaiting the host's ResumeAck must NOT steal the reply slot. Pre-fix the ack resolved
     * only the public call; the internal attempt hung until the reconnect window expired,
     * fired `markHostLost`, and drove the room terminal despite the successful resume.
     *
     * The host room's seam delays all outbound frames by 60 virtual ms so the ResumeAck is
     * reliably in flight when the public `resume()` lands (the deterministic single-threaded
     * stand-in for the multi-threaded interleaving).
     */
    @Test
    fun `public resume racing the internal auto-reconnect must not drive the room terminal`() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
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
            val faultyHostSeam = FaultySeam(serverLoom.host(Pattern("table-7")), backgroundScope)
            val hostRoom = SeamRoom(
                seam = faultyHostSeam,
                role = SessionRole.Host,
                memberName = "table-7",
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
            val nameOf: (Rendezvous) -> String = { rv ->
                when (rv) {
                    is Rendezvous.New -> rv.pattern.sessionName
                    is Rendezvous.Existing -> rv.tag.sessionName
                }
            }
            val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
            val tag = InMemoryTag("table-7")
            val joinerRoom = SeamRoom(
                seam = muxClient.join(tag),
                role = SessionRole.Joiner,
                memberName = "client",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { muxClient.join(tag) },
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }
            val token = assertNotNull(joinerRoom.resumeToken, "joiner must hold a resume token after admit")

            // Delay every host→joiner frame from here on, so the internal auto-reconnect's
            // ResumeAck is reliably still in flight when the public resume() lands.
            faultyHostSeam.setFaultProfile(FaultProfile.DelayAll(60.milliseconds, Direction.Outbound))

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
            val joinerResumed = async { joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }

            // Tear the base — the internal auto-reconnect re-weaves and sends its Resume.
            muxClient.closeBase()
            runCurrent()
            var guard = 0
            while (!joinerRoom.hasPendingResume() && guard++ < 10) advanceTimeBy(5L)
            assertTrue(
                joinerRoom.hasPendingResume(),
                "precondition: the internal auto-reconnect's resume must be in flight",
            )

            // Public resume() lands while the internal attempt awaits its (delayed) ResumeAck.
            val publicResume = async { joinerRoom.resume(token) }
            runCurrent()

            // Let the delayed ack arrive, then advance well past the reconnect window.
            val publicResult = withTimeoutOrNull(2.seconds) { publicResume.await() }
            val resumedEvent = withTimeoutOrNull(2.seconds) { joinerResumed.await() }
            repeat(8) { advanceTimeBy(100L) }

            assertAll(
                { assertNotNull(publicResult, "the public resume() must complete") },
                { assertIs<ResumeResult.Success>(publicResult) },
                { assertNotNull(resumedEvent, "the joiner must observe MembershipEvent.Resumed") },
                {
                    assertFalse(
                        hostLost.isCompleted,
                        "a public resume() racing the internal auto-reconnect must not drive the room " +
                            "terminal after a successful resume",
                    )
                },
            )
            hostLost.cancel()
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun TestScope.makeSeamRoom(
        seam: Seam,
        role: SessionRole,
        displayName: String,
        clock: () -> Instant,
        roomId: RoomId? = null,
    ): SeamRoom =
        SeamRoom(
            seam = seam,
            role = role,
            memberName = displayName,
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = roomId,
        ).also { it.start() }
}
