package us.tractat.kuilt.session.partition

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
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

    /**
     * [JoinerReconnectEvent.WindowOpened.detectedAt] is the `at` the controller was **handed**,
     * echoed unchanged — the identity of the partition episode this window belongs to (#1781).
     *
     * The fixture's [fixedClock] reads 0 while the drop is reported at [DROP_AT], so the two
     * candidate sources are distinguishable: an implementation that sampled a clock at the emit
     * site — which would name *when the announcement was made* rather than *which drop it is
     * about*, reintroducing exactly the ambiguity the field exists to remove — reads 0 here.
     */
    @Test
    fun `WindowOpened echoes the detection instant it was handed rather than a clock read`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val eventJob = async { ctrl.events.first() }
            ctrl.onPeerUnresponsive(peerA, at = DROP_AT)
            testScheduler.advanceTimeBy(1)

            val event = assertIs<JoinerReconnectEvent.WindowOpened>(eventJob.await())
            assertAll(
                {
                    assertEquals(
                        DROP_AT,
                        event.detectedAt,
                        "detectedAt must be the reported drop instant; 0 would mean it came from the clock",
                    )
                },
                {
                    assertEquals(
                        DROP_AT + windowMs,
                        event.expiresAt,
                        "…and the deadline is still measured from that same instant",
                    )
                },
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

    @Test
    fun `WindowExpired echoes the detection instant of the window it closes`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val eventsJob = async { ctrl.events.take(2).toList() }
            // Not 0: an `at` of 0 is indistinguishable from an un-set field and from a `clock()`
            // read (the injected clock returns 0), so it could not tell an echo from either.
            ctrl.onPeerUnresponsive(peerA, at = DETECTED_AT)
            testScheduler.advanceTimeBy(1)

            ctrl.expire(peerA, at = DETECTED_AT + 10L)
            testScheduler.advanceTimeBy(1)

            val expired = assertIs<JoinerReconnectEvent.WindowExpired>(eventsJob.await()[1])
            assertAll(
                {
                    assertEquals(
                        DETECTED_AT,
                        expired.detectedAt,
                        "the expiry must name the episode it closes, echoed from onPeerUnresponsive",
                    )
                },
                {
                    assertEquals(
                        DETECTED_AT + 10L,
                        expired.at,
                        "…and `at` stays the expiry instant, so the two are not the same field twice",
                    )
                },
            )
        }

    // ── Recovery closes the window without expiring it ────────────────────────

    /**
     * The #2556 root cause, at the controller: a peer restored by the liveness detector alone never
     * presents a token, so `tryResume` — the only other thing that closes a window — is never
     * reached, and the timer stayed armed behind a peer that was already back. Its `WindowExpired`
     * is what a room fans out as an authoritative farewell.
     *
     * The advance is a **full window past** the deadline, not up to it: a test that stopped at the
     * deadline could not distinguish "disarmed" from "not yet fired".
     */
    @Test
    fun `onPeerRecovered disarms the timer so no WindowExpired ever fires`() =
        runTest {
            val ctrl = controller(backgroundScope)
            val events = mutableListOf<JoinerReconnectEvent>()
            val collector = backgroundScope.launch { ctrl.events.collect { events += it } }
            testScheduler.runCurrent()

            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)
            ctrl.onPeerRecovered(peerA, at = 1L)
            testScheduler.advanceTimeBy(windowMs * 2)
            testScheduler.runCurrent()
            collector.cancel()

            assertAll(
                {
                    assertEquals(
                        1,
                        events.size,
                        "rig: exactly the WindowOpened must have been seen — observed $events",
                    )
                },
                { assertIs<JoinerReconnectEvent.WindowOpened>(events.single()) },
            )
        }

    /**
     * Recovery closes the window **without expiring it**, and the difference is observable: an
     * expiry would leave the window terminally closed, so the next `tryResume` would answer
     * [ResumeResult.WindowClosed] — "re-join fresh" — for a peer whose window merely stopped being
     * needed. Routing recovery through `expire` would pass the test above and fail this one.
     */
    @Test
    fun `tryResume after onPeerRecovered is not answered as a closed window`() =
        runTest {
            val ctrl = controller(backgroundScope)
            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)

            ctrl.onPeerRecovered(peerA, at = 1L)
            testScheduler.advanceTimeBy(1)

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 2L)
            assertEquals(
                ResumeResult.Success,
                ctrl.tryResume(token, at = 2L),
                "a window whose peer recovered is closed, not expired — an in-flight resume must " +
                    "still be honoured rather than told the seat is gone",
            )
        }

    @Test
    fun `onPeerRecovered for a peer with no window is a no-op`() =
        runTest {
            val ctrl = controller(backgroundScope)
            val events = mutableListOf<JoinerReconnectEvent>()
            val collector = backgroundScope.launch { ctrl.events.collect { events += it } }
            testScheduler.runCurrent()

            ctrl.onPeerRecovered(peerB, at = 5L)
            testScheduler.advanceTimeBy(windowMs * 2)
            testScheduler.runCurrent()
            collector.cancel()

            assertEquals(emptyList(), events, "no window was open, so nothing may be announced")
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
    fun `tryResume for peer with no open window returns WindowNotYetOpen`() =
        runTest {
            val ctrl = controller(backgroundScope)

            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 0L)
            val result = ctrl.tryResume(token, at = 0L)
            assertIs<ResumeResult.WindowNotYetOpen>(result)
        }

    /**
     * The #1572 split. A window that has **not opened yet** is the fast-reconnect race — the
     * joiner re-wove before the host's detector fired, and a retry a moment later recovers it.
     * A window that **expired** (or whose token was already consumed) is terminal: no retry can
     * ever succeed. Folding both into one result is what forced the joiner to retry blindly for
     * the whole window before surfacing a genuinely terminal refusal.
     */
    @Test
    fun `a window that never opened is distinguishable from one that expired`() =
        runTest {
            val ctrl = controller(backgroundScope)
            val token = ResumeToken(peerId = peerA, roomId = sessionId, issuedAt = 0L)

            val neverOpened = ctrl.tryResume(token, at = 0L)

            ctrl.onPeerUnresponsive(peerA, at = 0L)
            testScheduler.advanceTimeBy(1)
            ctrl.expire(peerA, at = 1L)
            testScheduler.advanceTimeBy(1)
            val expired = ctrl.tryResume(token, at = 2L)

            assertAll(
                { assertIs<ResumeResult.WindowNotYetOpen>(neverOpened) },
                { assertIs<ResumeResult.WindowClosed>(expired) },
            )
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

    private companion object {
        /** A drop instant the fixture's always-zero clock could never produce. */
        const val DROP_AT = 7_000L

        /** The same, for the expiry echo — distinct from [DROP_AT] so neither can stand in. */
        const val DETECTED_AT = 3_500L
    }
}

