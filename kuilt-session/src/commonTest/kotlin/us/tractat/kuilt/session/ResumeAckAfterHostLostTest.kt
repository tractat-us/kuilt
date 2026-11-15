@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeSecret
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A `ResumeAck` racing a joiner's expiring reconnect window must not resume a room that is ending
 * (#3094). The rule: the room's lock orders the ack against the window's verdict, and whichever
 * takes it first wins.
 *
 * When a joiner's reconnect window expires, the window's cancellation leaves the resume flight
 * installed, and `HostLost` goes out before `leave` takes that flight. On a multi-threaded
 * dispatcher the room's frame handler can run a `ResumeAck` anywhere in that stretch.
 *
 * None of these gaps has a suspension point, so a test dispatcher cannot schedule a frame into one.
 * These tests play the second thread by hand instead, from a hook that runs inside the gap, and run
 * the ack through the room's own frame handler at once by calling the collector the room handed to
 * [ScriptedSeam]'s `incoming`. Each rig records that the flight was still pending when it fired, so
 * a rig that lands outside its gap fails rather than passing quietly. The hooks:
 * - after the window's timer fires and before the verdict: the cancelled resume's collection of the
 *   seam's `state` ending;
 * - after the verdict and before `HostLost`: the room's clock, read for the `HostLost` timestamp;
 * - at `HostLost` itself: an unconfined collector of `events`, which runs inside the emission.
 */
class ResumeAckAfterHostLostTest {

    private val joinerId = PeerId("joiner")
    private val hostId = PeerId("host")

    // resumeTimeout outlasts the window, so the one flight is still waiting for its verdict when
    // the window expires, and so is the caller that joins it.
    private val config = HeartbeatConfig(
        interval = 50.milliseconds,
        timeout = 500.milliseconds,
        reconnectWindow = 2.seconds,
        resumeTimeout = 10.seconds,
    )

    @Test
    fun `a ResumeAck handled after HostLost neither resumes the room nor reports Success`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            val (room, token) = admittedJoiner(seam)

            val events = mutableListOf<MembershipEvent>()
            var pendingWhenRigFired: Boolean? = null
            var pendingAfterAck: Boolean? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                room.events.collect { event ->
                    events += event
                    if (event is MembershipEvent.HostLost && pendingWhenRigFired == null) {
                        pendingWhenRigFired = room.hasPendingResume()
                        seam.deliverNow(AdmitMessage.ResumeAck)
                        pendingAfterAck = room.hasPendingResume()
                    }
                }
            }

            val result = expireWindow(room, seam, token)

            val hostLostAt = events.indexOfFirst { it is MembershipEvent.HostLost }
            val afterHostLost = if (hostLostAt < 0) emptyList() else events.drop(hostLostAt + 1)
            assertAll(
                {
                    assertIs<FailureReason.WindowExpired>(
                        (events.getOrNull(hostLostAt) as? MembershipEvent.HostLost)?.reason,
                        "precondition: the window expired into HostLost (events: $events)",
                    )
                },
                { assertEquals(true, pendingWhenRigFired, "the rig fired inside the gap: the flight was still pending") },
                { assertEquals(false, pendingAfterAck, "the ack reached the flight") },
                { assertEquals(ResumeResult.WindowClosed, result, "a resume on a host-lost room must not report Success") },
                {
                    assertFalse(
                        afterHostLost.any { it is MembershipEvent.Resumed },
                        "no Resumed may follow HostLost (after it: $afterHostLost)",
                    )
                },
            )
        }

    /**
     * The window was decided lost: the episode's job is cleared, and `HostLost` is not out yet. The
     * room's only call in there is its clock, read for the `HostLost` timestamp.
     */
    @Test
    fun `a ResumeAck handled once the window is decided lost reports WindowClosed`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            var roomRef: SeamRoom? = null
            var rig: AckRig? = null
            var events: List<MembershipEvent> = emptyList()
            val (room, token) = admittedJoiner(seam) {
                val r = roomRef
                // Decided (no job) but not yet torn down (the flight is still pending): only the gap.
                if (r != null && rig == null && r.hasPendingResume() && !r.hasReconnectInFlight()) {
                    rig = fireAck(r, seam, events)
                }
            }
            roomRef = room
            events = recordEvents(room)

            val result = expireWindow(room, seam, token)

            val fired = assertNotNull(rig, "precondition: the rig fired between the verdict and HostLost")
            assertAll(
                {
                    assertTrue(
                        events.any { (it as? MembershipEvent.HostLost)?.reason is FailureReason.WindowExpired },
                        "precondition: the window expired into HostLost (events: $events)",
                    )
                },
                { assertTrue(fired.pendingBefore, "the rig fired inside the gap: the flight was still pending") },
                { assertFalse(fired.hostLostAnnounced, "the rig fired before HostLost went out") },
                { assertFalse(fired.pendingAfter, "the ack reached the flight") },
                { assertEquals(ResumeResult.WindowClosed, result, "an ack after the verdict must not report Success") },
                { assertFalse(events.any { it is MembershipEvent.Resumed }, "a room decided lost resumes nothing (events: $events)") },
            )
        }

    /**
     * The other side of the rule. The window's timer has fired but the episode is not decided yet,
     * and an ack takes the lock first. The host has taken the joiner back, so the room resumes.
     */
    @Test
    fun `a ResumeAck handled after the window timer fires but before the verdict resumes the room`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            val (room, token) = admittedJoiner(seam)
            val events = recordEvents(room)
            val deadline = testScheduler.currentTime + config.reconnectWindow.inWholeMilliseconds
            var rig: AckRig? = null
            var firedAt: Long? = null
            seam.onStateCollectionEnded = { cancelled ->
                // A cancelled collection, at the deadline, with the episode undecided and its flight
                // unanswered: the window's own timeout is unwinding the resume.
                if (rig == null && cancelled && testScheduler.currentTime >= deadline &&
                    room.hasReconnectInFlight() && room.hasPendingResume()
                ) {
                    firedAt = testScheduler.currentTime
                    rig = fireAck(room, seam, events)
                }
            }

            val result = expireWindow(room, seam, token)
            // The room is live: a fresh resume() goes to the host rather than answering WindowClosed.
            backgroundScope.launch { room.resume(token) }
            runCurrent()
            val resumesAfterAnotherCall = seam.resumesSent

            val fired = assertNotNull(rig, "precondition: the rig fired after the timer and before the verdict")
            assertAll(
                { assertEquals(deadline, firedAt, "the rig fired at the window's deadline") },
                { assertTrue(fired.pendingBefore, "the flight was still pending when the rig fired") },
                { assertFalse(fired.pendingAfter, "the ack reached the flight") },
                { assertEquals(ResumeResult.Success, result, "an ack that wins the lock resumes the room") },
                { assertEquals(1, events.count { it is MembershipEvent.Resumed }, "one Resumed (events: $events)") },
                { assertFalse(events.any { it is MembershipEvent.HostLost }, "a resumed room is not host-lost (events: $events)") },
                { assertEquals(2, resumesAfterAnotherCall, "the room is live: another resume() sends another Resume") },
            )
        }

    /** What a hand-played second thread saw: the flight before and after its ack, and whether `HostLost` was out. */
    private class AckRig(val pendingBefore: Boolean, val pendingAfter: Boolean, val hostLostAnnounced: Boolean)

    private fun TestScope.recordEvents(room: SeamRoom): List<MembershipEvent> {
        val events = mutableListOf<MembershipEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            room.events.collect { events += it }
        }
        return events
    }

    /**
     * Runs a `ResumeAck` through the room's frame handler now, on this stack, from a caller that cannot
     * suspend: an undispatched coroutine runs inline up to its first suspension, and the handler has
     * none before it takes the flight, which [AckRig.pendingAfter] confirms.
     */
    private fun TestScope.fireAck(room: SeamRoom, seam: ScriptedSeam, events: List<MembershipEvent>): AckRig {
        val before = room.hasPendingResume()
        val announced = events.any { it is MembershipEvent.HostLost }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.deliverNow(AdmitMessage.ResumeAck) }
        return AckRig(pendingBefore = before, pendingAfter = room.hasPendingResume(), hostLostAnnounced = announced)
    }

    /** A joiner the scripted host has admitted, holding a resume token. [onClock] runs on every clock read. */
    private fun TestScope.admittedJoiner(seam: ScriptedSeam, onClock: () -> Unit = {}): Pair<SeamRoom, ResumeToken> {
        val room = SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            memberName = "joiner",
            scope = backgroundScope,
            clock = {
                onClock()
                Instant.fromEpochMilliseconds(testScheduler.currentTime)
            },
            heartbeatConfig = config,
            roomId = null,
            reweave = {
                seam.heal()
                seam
            },
        ).also { it.start() }
        runCurrent()

        // The host admits the joiner: its own Welcome identifies the host and mints the token.
        seam.deliver(
            AdmitMessage.Welcome(
                assignedPeerId = joinerId.value,
                displayName = "joiner",
                sessionId = joinerId.value,
                roomId = "room-1",
                resumeSecret = ResumeSecret.generate(),
            ),
        )
        runCurrent()
        return room to assertNotNull(room.resumeToken, "precondition: the joiner holds a resume token")
    }

    /**
     * Tears the link, lets the joiner send a `Resume` the host never answers, joins that flight with a
     * caller's own `resume()`, and runs the clock past the window. Returns what that caller got.
     */
    private suspend fun TestScope.expireWindow(
        room: SeamRoom,
        seam: ScriptedSeam,
        token: ResumeToken,
    ): ResumeResult.JoinerOutcome {
        seam.tear()
        runCurrent()
        assertAll(
            { assertEquals(1, seam.resumesSent, "precondition: the Resume went out") },
            { assertTrue(room.hasPendingResume(), "precondition: its verdict has not come back") },
        )
        val joined: Deferred<ResumeResult.JoinerOutcome> = async { room.resume(token) }
        runCurrent()

        advanceTimeBy(config.reconnectWindow.inWholeMilliseconds + 1)
        runCurrent()
        return joined.await()
    }
}

/**
 * A joiner's seam that the test plays the host on. It starts `Woven` with the host listed, tears on
 * [tear], and is healed in place by the room's re-weave. It counts the `Resume` frames sent to the
 * host, and runs [onStateCollectionEnded] as each collection of [state] ends, saying whether the
 * collecting coroutine was cancelled.
 *
 * [deliver] queues a frame for the room's collector coroutine, which is how a real fabric delivers.
 * [deliverNow] instead runs the frame through the room's collector on the caller's own stack, now,
 * which is what a second thread running the room's frame handler would do.
 */
private class ScriptedSeam(
    override val selfId: PeerId,
    private val hostId: PeerId,
) : Seam {
    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    var onStateCollectionEnded: (cancelled: Boolean) -> Unit = {}
    override val state: StateFlow<SeamState> = EndHookedStateFlow(_state.asStateFlow()) { onStateCollectionEnded(it) }
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId, hostId)).asStateFlow()

    // Bounded, like every delivery buffer here: the test queues one frame.
    private val frames = Channel<Swatch>(capacity = 8)
    private var collector: FlowCollector<Swatch>? = null

    override val incoming: Flow<Swatch> = object : Flow<Swatch> {
        override suspend fun collect(collector: FlowCollector<Swatch>) {
            this@ScriptedSeam.collector = collector
            for (frame in frames) collector.emit(frame)
        }
    }

    var resumesSent: Int = 0
        private set

    fun deliver(message: AdmitMessage) {
        frames.trySend(Swatch(AdmitMessage.encode(message), sender = hostId)).getOrThrow()
    }

    suspend fun deliverNow(message: AdmitMessage) {
        checkNotNull(collector) { "the room is not collecting" }.emit(Swatch(AdmitMessage.encode(message), sender = hostId))
    }

    fun tear() {
        _state.value = SeamState.Torn(CloseReason.Normal)
    }

    fun heal() {
        _state.value = SeamState.Woven
    }

    override suspend fun broadcast(payload: ByteArray) = Unit

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (peer == hostId && AdmitMessage.decode(payload) is AdmitMessage.Resume) resumesSent++
    }

    override suspend fun close(reason: CloseReason) {
        _state.value = SeamState.Torn(reason)
    }
}

/** [source], plus a hook run as each collection of it ends, told whether the collector was cancelled. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class EndHookedStateFlow<T>(
    private val source: StateFlow<T>,
    private val onEnd: (cancelled: Boolean) -> Unit,
) : StateFlow<T> {
    override val value: T get() = source.value
    override val replayCache: List<T> get() = source.replayCache

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        try {
            source.collect(collector)
        } finally {
            onEnd(currentCoroutineContext()[Job]?.isCancelled == true)
        }
    }
}
