@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * A `ResumeAck` handled after the joiner has gone host-lost must not resume the room (#3094).
 *
 * When a joiner's reconnect window expires, the window's cancellation leaves the resume flight
 * installed, and `markHostLost` emits `HostLost` before `leave` takes that flight. On a
 * multi-threaded dispatcher the room's frame handler can run a `ResumeAck` in that gap: it took the
 * flight, emitted `Resumed` after `HostLost`, and completed `resume()` with `Success` on a room that
 * was ending.
 *
 * The gap has no suspension point, so a test dispatcher cannot schedule a frame into it. This test
 * plays the second thread by hand instead. An unconfined collector of `events` runs inline inside
 * the `HostLost` emission, which is inside the gap, and from there it runs the ack through the
 * room's own frame handler at once, by calling the collector the room handed to [ScriptedSeam]'s
 * `incoming`. The rig records that the flight was still pending when it fired, so a rig that lands
 * outside the gap fails rather than passing quietly.
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
            val room = SeamRoom(
                seam = seam,
                role = SessionRole.Joiner,
                memberName = "joiner",
                scope = backgroundScope,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
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
            val token = assertNotNull(room.resumeToken, "precondition: the joiner holds a resume token")

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

            // The link tears; the joiner re-weaves and sends its Resume, which the host never answers.
            seam.tear()
            runCurrent()
            assertAll(
                { assertEquals(1, seam.resumesSent, "precondition: the Resume went out") },
                { assertTrue(room.hasPendingResume(), "precondition: its verdict has not come back") },
            )
            // A caller of resume() joins the waiting flight.
            val joined = async { room.resume(token) }
            runCurrent()

            advanceTimeBy(config.reconnectWindow.inWholeMilliseconds + 1)
            runCurrent()
            val result = joined.await()

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
}

/**
 * A joiner's seam that the test plays the host on. It starts `Woven` with the host listed, tears on
 * [tear], and is healed in place by the room's re-weave. It counts the `Resume` frames sent to the
 * host.
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
    override val state: StateFlow<SeamState> = _state.asStateFlow()
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId, hostId)).asStateFlow()

    private val frames = Channel<Swatch>(Channel.UNLIMITED)
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
        frames.trySend(Swatch(AdmitMessage.encode(message), sender = hostId))
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
