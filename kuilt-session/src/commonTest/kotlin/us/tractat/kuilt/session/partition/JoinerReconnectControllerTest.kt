package us.tractat.kuilt.session.partition

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [DefaultJoinerReconnectController].
 *
 * All tests run under [runTest] (virtual time). The injected [clock] always
 * returns 0 so [testScheduler.advanceTimeBy] drives the only time source —
 * no wall-clock dependency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinerReconnectControllerTest {
    private val sessionId = RoomId("session-abc")
    private val otherSession = RoomId("session-xyz")
    private val peerA = PeerId("peer-a")
    private val peerB = PeerId("peer-b")
    private val windowMs = 60_000L

    // Clock always returns 0; virtual time is advanced via testScheduler.
    private val fixedClock: () -> Long = { 0L }

    private fun controller(scope: kotlinx.coroutines.CoroutineScope) =
        DefaultJoinerReconnectController(
            roomId = sessionId,
            reconnectWindowMs = windowMs,
            clock = fixedClock,
            scope = scope,
        )

    // ── Window opened ─────────────────────────────────────────────────────────

    @Test
    fun `WindowOpened event fires when peer goes unresponsive`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val eventJob = async { ctrl.events.first() }
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            val event = eventJob.await()
            assertIs<JoinerReconnectEvent.WindowOpened>(event)
            assertAll(
                { assertEquals(peerA, event.peerId) },
                { assertEquals(windowMs, event.expiresAt) },
            )
        }

    // ── Happy-path resume ─────────────────────────────────────────────────────

    @Test
    fun `resume within window returns Success and emits Resumed`() =
        runTest {
            val ctrl = controller(backgroundScope)

            // Subscribe before triggering so we catch all events.
            val eventsJob = async { ctrl.events.take(2).toList() }

            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1) // flush WindowOpened

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 30_000L)
            val result = ctrl.tryResume(token, at = 30_000L)

            testScheduler.advanceUntilIdle()
            val events = eventsJob.await()

            assertAll(
                { assertIs<ResumeResult.Success>(result) },
                { assertIs<JoinerReconnectEvent.WindowOpened>(events[0]) },
                { assertIs<JoinerReconnectEvent.Resumed>(events[1]) },
                { assertEquals(peerA, (events[1] as JoinerReconnectEvent.Resumed).peerId) },
            )
        }

    @Test
    fun `second tryResume after success returns WindowClosed`() =
        runTest {
            val ctrl = controller(backgroundScope)
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 1L)
            ctrl.tryResume(token, at = 1L)

            val secondResult = ctrl.tryResume(token, at = 2L)
            assertIs<ResumeResult.WindowClosed>(secondResult)
        }

    // ── Window expiry ─────────────────────────────────────────────────────────

    @Test
    fun `resume after window expiry returns WindowClosed`() =
        runTest {
            val ctrl = controller(backgroundScope)
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            // Advance past the full window.
            testScheduler.advanceTimeBy(windowMs)
            testScheduler.runCurrent()

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = windowMs + 1L)
            val result = ctrl.tryResume(token, at = windowMs + 1L)
            assertIs<ResumeResult.WindowClosed>(result)
        }

    @Test
    fun `WindowExpired event fires at exactly reconnectWindow after unresponsive`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val eventsJob = async { ctrl.events.take(2).toList() }
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1) // flush WindowOpened

            testScheduler.advanceTimeBy(windowMs - 1)
            testScheduler.runCurrent()

            // One ms before expiry — should only have WindowOpened so far.
            // Now cross the boundary.
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            val events = eventsJob.await()
            assertAll(
                { assertIs<JoinerReconnectEvent.WindowOpened>(events[0]) },
                { assertIs<JoinerReconnectEvent.WindowExpired>(events[1]) },
                { assertEquals(peerA, (events[1] as JoinerReconnectEvent.WindowExpired).peerId) },
            )
        }

    // ── force-expire ──────────────────────────────────────────────────────────

    @Test
    fun `expire short-circuits timer and emits WindowExpired`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val eventsJob = async { ctrl.events.take(2).toList() }
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            ctrl.expire(peerA, at = 1_000L)
            testScheduler.advanceTimeBy(1)

            val events = eventsJob.await()
            assertAll(
                { assertIs<JoinerReconnectEvent.WindowOpened>(events[0]) },
                { assertIs<JoinerReconnectEvent.WindowExpired>(events[1]) },
            )
        }

    @Test
    fun `tryResume after expire returns WindowClosed`() =
        runTest {
            val ctrl = controller(backgroundScope)
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            ctrl.expire(peerA, at = 1_000L)
            testScheduler.advanceTimeBy(1)

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 1_001L)
            val result = ctrl.tryResume(token, at = 1_001L)
            assertIs<ResumeResult.WindowClosed>(result)
        }

    // ── Session mismatch ──────────────────────────────────────────────────────

    @Test
    fun `mismatched RoomId returns TokenInvalid with session-mismatch reason`() =
        runTest {
            val ctrl = controller(backgroundScope)
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            val token = ResumeToken(peerId = peerA, roomId = otherSession, issuedAt = 1L)
            val result = ctrl.tryResume(token, at = 1L)

            assertIs<ResumeResult.TokenInvalid>(result)
            assertEquals("session-mismatch", result.reason)
        }

    @Test
    fun `mismatched RoomId does not close the window`() =
        runTest {
            val ctrl = controller(backgroundScope)
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            // Wrong session — should bounce.
            val badToken = ResumeToken(peerId = peerA, roomId = otherSession, issuedAt = 1L)
            ctrl.tryResume(badToken, at = 1L)

            // Correct session — window must still be open.
            val goodToken = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 2L)
            val result = ctrl.tryResume(goodToken, at = 2L)
            assertIs<ResumeResult.Success>(result)
        }

    // ── Unknown peer ──────────────────────────────────────────────────────────

    @Test
    fun `tryResume for peer with no open window returns WindowClosed`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 0L)
            val result = ctrl.tryResume(token, at = 0L)
            assertIs<ResumeResult.WindowClosed>(result)
        }

    // ── Per-peer independence ─────────────────────────────────────────────────

    @Test
    fun `peerA expiring does not affect peerB window`() =
        runTest {
            val ctrl = controller(backgroundScope)

            ctrl.onPeerUnresponsive(peerA, at = 0L)
            ctrl.onPeerUnresponsive(peerB, at = 0L)
            testScheduler.advanceTimeBy(1)

            ctrl.expire(peerA, at = 1_000L)
            testScheduler.advanceTimeBy(1)

            val tokenB = ResumeToken(peerId = peerB, roomId = sessionId, issuedAt = 1_001L)
            val result = ctrl.tryResume(tokenB, at = 1_001L)
            assertIs<ResumeResult.Success>(result)
        }

    @Test
    fun `two peers have independent windows that both expire independently`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val eventsJob = async { ctrl.events.take(4).toList() }
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            ctrl.onPeerUnresponsive(peerB, at = 0L)
            testScheduler.advanceTimeBy(1)

            // Advance past the full window so both expire.
            testScheduler.advanceTimeBy(windowMs)
            testScheduler.runCurrent()

            val events = eventsJob.await()
            val expired = events.filterIsInstance<JoinerReconnectEvent.WindowExpired>()
            assertAll(
                { assertEquals(2, expired.size) },
                { assertEquals(setOf(peerA, peerB), expired.map { it.peerId }.toSet()) },
            )
        }

    // ── Default constant ──────────────────────────────────────────────────────

    @Test
    fun `default reconnect window is 60s`() {
        assertEquals(60_000L, DefaultJoinerReconnectController.DEFAULT_RECONNECT_WINDOW_MS)
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
