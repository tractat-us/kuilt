@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * **Characterization** tests for #1635 / #1618 Track B — a joiner whose **host goes silent by
 * heartbeat `Timeout`** while its seam stays `Woven` (the airplane-drop lane, as opposed to a
 * transport `Torn`/`TransportClosed` tear — that is [JoinerReconnectTest]).
 *
 * These document the CURRENT, already-correct behavior: the joiner-host `Timeout` routes to
 * [SeamRoom.markPartitioned] (not the resume machine), which flips liveness to
 * [Liveness.Partitioned] with an honest [ReconnectReason.LinkTimeout], and the per-host
 * [us.tractat.kuilt.liveness.HeartbeatPartitionDetector] then polls the reconnect window:
 * frames resume in-window → [MembershipEvent.Recovered]; sustained silence →
 * [MembershipEvent.HostLost] with [FailureReason.WindowExpired]. No re-weave, no resume
 * handshake, no seam leak — i.e. the self-healing case already satisfies Track B's "Option A"
 * contract, which is why routing `Timeout` into the resume machine would only regress it.
 *
 * Harness note: the silence is injected with a [FaultySeam] `partition()` (drop all frames) which
 * leaves `state` `Woven` and `peers` intact — so the detector fires `Timeout`. A lifecycle seam's
 * `Weaving` collapse would drop the host from `peers` and fire `TransportClosed` instead — the
 * wrong lane for this contract.
 *
 * Timing (fast config): interval 100 ms, timeout 200 ms, reconnect window 500 ms.
 */
class JoinerHostTimeoutRecoveryTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    private val nameOf: (Rendezvous) -> String = { rv ->
        when (rv) {
            is Rendezvous.New -> rv.pattern.sessionName
            is Rendezvous.Existing -> rv.tag.sessionName
        }
    }

    private class TimeoutHarness(
        val joinerRoom: SeamRoom,
        val joinerSeam: FaultySeam,
        val reweaveCount: () -> Int,
    )

    private suspend fun TestScope.timeoutHarness(
        clock: () -> Instant,
    ): TimeoutHarness {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
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
                return hubMesh(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
            }
        }
        val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
        val tag = InMemoryTag("table-7")
        // Wrap the joiner's live seam so heartbeats can be dropped WITHOUT tearing it: state stays
        // Woven and peers intact, so the detector fires Timeout (silence), not TransportClosed.
        val joinerSeam = FaultySeam(muxClient.join(tag), backgroundScope)
        var reweaveCount = 0
        val joinerRoom = SeamRoom(
            seam = joinerSeam,
            role = SessionRole.Joiner,
            memberName = "client",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = null,
            reweave = { reweaveCount++; muxClient.join(tag) },
        ).also { it.start() }

        return TimeoutHarness(joinerRoom, joinerSeam, reweaveCount = { reweaveCount })
    }

    @Test
    fun `joiner-host silence recovers via Recovered when frames resume in-window - no reweave`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // The detector's recovery check is silenceMs = clock() - lastSeen, so the injected clock
            // must ADVANCE in lockstep with virtual time (a frozen clock never registers recovery).
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val h = timeoutHarness(clock)

            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken, "joiner must hold a resume token after admit")

            val partitioned =
                async { h.joinerRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first() }
            val recovered =
                async { h.joinerRoom.events.filterIsInstance<MembershipEvent.Recovered>().first() }
            val hostLost =
                async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // Drop all heartbeats — the seam stays Woven (host still in peers), so the detector
            // fires Timeout, not TransportClosed. Advance past the 200 ms silence timeout.
            h.joinerSeam.partition()
            repeat(3) { clockMs += 100L; advanceTimeBy(100L) }

            val p = partitioned.await()
            assertEquals(
                ReconnectReason.LinkTimeout,
                p.reason,
                "a silent host drop must be classified LinkTimeout, not TransportClosed",
            )
            assertIs<SeamState.Woven>(
                h.joinerSeam.state.value,
                "the seam must NOT have torn — this is the Timeout lane, not TransportClosed",
            )

            // Heal well within the 500 ms window — frames flow again; the detector observes a fresh
            // frame (silenceMs drops below the 200 ms timeout) → PeerRecovered → Recovered.
            h.joinerSeam.heal()
            repeat(3) { clockMs += 100L; advanceTimeBy(100L) }

            assertIs<MembershipEvent.Recovered>(recovered.await())
            assertFalse(
                hostLost.isCompleted,
                "an in-window heartbeat recovery must not fall to HostLost",
            )
            assertEquals(0, h.reweaveCount(), "the self-healing path must NOT mint a throwaway seam")
            hostLost.cancel()
        }

    @Test
    fun `joiner-host sustained silence falls to HostLost WindowExpired - no reweave`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val h = timeoutHarness(clock)

            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken)

            val hostLost =
                async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // Silence the host and never heal — advance past the 500 ms window with margin.
            h.joinerSeam.partition()
            repeat(9) { clockMs += 100L; advanceTimeBy(100L) }

            val event = hostLost.await()
            assertEquals(
                FailureReason.WindowExpired,
                event.reason,
                "a sustained silent host drop terminates as WindowExpired (honest window elapse)",
            )
            assertEquals(0, h.reweaveCount(), "the terminal path must NOT mint a throwaway seam")
        }
}
