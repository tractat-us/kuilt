@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import us.tractat.kuilt.identity.test.fakeKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
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
import kotlinx.coroutines.test.TestDispatcher
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
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.admit.ReturnKind
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
import kotlin.coroutines.CoroutineContext

/**
 * The host's `Welcome` closing a joiner's reconnect — a `ResumeAck` before #3159 Task 1.7 — racing its
 * expiring reconnect window must not resume a room that is ending
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
    fun `a closing Welcome handled after HostLost neither resumes the room nor reports Success`() =
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
                        seam.deliverNow(restoredWelcome)
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
    fun `a closing Welcome handled once the window is decided lost reports WindowClosed`() =
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
    fun `a closing Welcome handled after the window timer fires but before the verdict resumes the room`() =
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

    /**
     * An episode an ack resumed leaves nothing behind for the next one. Every resuming ack marks its
     * episode resumed, and the verdict honours that mark even after the window's timer has fired. A
     * mark carried into a later episode whose window is lost would resume that one too: no
     * `HostLost`, its flight left pending, and a host detector restored on a dead link.
     */
    @Test
    fun `an episode closed by a Welcome does not resume a later episode whose window is lost`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            val (room, _) = admittedJoiner(seam)
            val events = recordEvents(room)
            // The host introduces itself, so the joiner monitors it: a later tear reaches the room only
            // through that detector, since the tear watcher fires for the first tear alone.
            seam.deliver(AdmitMessage.Welcome(assignedPeerId = hostId.value, displayName = "host", sessionId = hostId.value, key = TEST_KEY_B))
            runCurrent()
            val monitoredAtStart = room.hasDetector(hostId)

            // Episode one: the host answers the Resume, and its ack resumes the room.
            seam.tear()
            runCurrent()
            val firstResumeSent = seam.resumesSent == 1 && room.hasPendingResume()
            challengeReHello(seam)
            seam.deliver(restoredWelcome)
            runCurrent()
            val firstEnded = !room.hasReconnectInFlight()
            val monitoredAfterFirst = room.hasDetector(hostId)
            val resumedByFirst = events.count { it is MembershipEvent.Resumed }

            // Episode two: the host never answers, and the window expires. The restored detector sees
            // the host leave the seam's peers and starts the episode.
            seam.dropHost()
            seam.tear()
            runCurrent()
            val secondResumeSent = seam.resumesSent == 2 && room.hasPendingResume()
            advanceTimeBy(config.reconnectWindow.inWholeMilliseconds + 1)
            runCurrent()

            assertAll(
                { assertTrue(monitoredAtStart, "precondition: the joiner monitors its host") },
                { assertTrue(firstResumeSent, "precondition: episode one sent a Resume and awaited its verdict") },
                { assertEquals(1, resumedByFirst, "precondition: the ack resumed episode one (events: $events)") },
                { assertTrue(firstEnded, "precondition: episode one ended before the next tear") },
                { assertTrue(monitoredAfterFirst, "precondition: episode one restored the host detector") },
                { assertTrue(secondResumeSent, "precondition: episode two sent a Resume and awaited its verdict") },
                {
                    assertTrue(
                        events.any { (it as? MembershipEvent.HostLost)?.reason is FailureReason.WindowExpired },
                        "episode two's window expired, so the room is host-lost (events: $events)",
                    )
                },
                { assertEquals(1, events.count { it is MembershipEvent.Resumed }, "episode two resumed nothing (events: $events)") },
                { assertFalse(room.hasPendingResume(), "the lost room's teardown took episode two's flight") },
            )
        }

    /**
     * A `Welcome` left over from the Hello that closed episode one reaches the room after episode two
     * began and before it says Hello: here, while the re-weave is still under way, so no flight is
     * installed yet. Its sender answered no challenge of episode two, so it closes nothing, and episode
     * two goes on to say Hello (review round 1 on #3614).
     */
    @Test
    fun `a stale Welcome before the next episode says Hello closes nothing`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            var gate: CompletableDeferred<Unit>? = null
            val (room, _) = admittedJoiner(seam, onReweave = { gate?.await() })
            val events = recordEvents(room)
            closeEpisodeOne(room, seam, events)

            gate = CompletableDeferred()
            seam.dropHost()
            seam.tear()
            runCurrent()
            val startedBeforeHello = room.hasReconnectInFlight() && !room.hasPendingResume() && seam.resumesSent == 1
            seam.deliver(restoredWelcome)
            runCurrent()
            val resumedByStale = events.count { it is MembershipEvent.Resumed } - 1
            checkNotNull(gate).complete(Unit)
            runCurrent()

            assertAll(
                { assertTrue(startedBeforeHello, "precondition: episode two began and had not said Hello") },
                { assertEquals(0, resumedByStale, "the stale Welcome closed nothing (events: $events)") },
                { assertEquals(2, seam.resumesSent, "episode two said Hello") },
                { assertTrue(room.hasPendingResume(), "episode two awaits its Hello's answer") },
            )
            room.leave()
        }

    /**
     * The same stale `Welcome`, now with episode two's flight installed and its Hello waiting for the
     * host to be addressable. Taken, it would settle the flight as a restore and leave the Hello unsent,
     * so the episode would close on a link nobody re-admitted.
     */
    @Test
    fun `a stale Welcome while the next Hello waits for its host closes nothing`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            val (room, _) = admittedJoiner(seam)
            val events = recordEvents(room)
            closeEpisodeOne(room, seam, events)

            seam.healListsHost = false
            seam.dropHost()
            seam.tear()
            runCurrent()
            val flightWaiting = room.hasPendingResume() && seam.resumesSent == 1
            seam.deliver(restoredWelcome)
            runCurrent()
            val resumedByStale = events.count { it is MembershipEvent.Resumed } - 1
            seam.listHost()
            runCurrent()

            assertAll(
                { assertTrue(flightWaiting, "precondition: episode two's flight waits to send its Hello") },
                { assertEquals(0, resumedByStale, "the stale Welcome closed nothing (events: $events)") },
                { assertEquals(2, seam.resumesSent, "episode two said Hello once its host was back") },
                { assertTrue(room.hasPendingResume(), "episode two awaits its Hello's answer") },
            )
            room.leave()
        }

    /**
     * A terminal `Reject` from the host that answered episode one's Hello reaches the room in the same
     * window. The host has challenged nothing of episode two, so the `Reject` is about another exchange
     * and does not end the episode (Amendment C).
     */
    @Test
    fun `a stale terminal Reject while the next Hello waits for its host ends nothing`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            val (room, _) = admittedJoiner(seam)
            val events = recordEvents(room)
            closeEpisodeOne(room, seam, events)

            seam.healListsHost = false
            seam.dropHost()
            seam.tear()
            runCurrent()
            val flightWaiting = room.hasPendingResume() && seam.resumesSent == 1
            seam.deliver(AdmitMessage.Reject("key-conflict: about an earlier exchange", RejectCode.KeyConflict))
            runCurrent()
            seam.listHost()
            runCurrent()

            assertAll(
                { assertTrue(flightWaiting, "precondition: episode two's flight waits to send its Hello") },
                { assertFalse(events.any { it is MembershipEvent.HostLost }, "the stale Reject ended nothing (events: $events)") },
                { assertEquals(2, seam.resumesSent, "episode two said Hello once its host was back") },
                { assertTrue(room.hasPendingResume(), "episode two awaits its Hello's answer") },
            )
            room.leave()
        }

    /**
     * The window was decided lost, and before `HostLost` goes out the host's `Farewell` tells the room
     * it was superseded. Whichever claim takes the lock first ends the room, and only it: here the
     * window's verdict did, so the room is host-lost and not also superseded (review round 1 on #3614).
     *
     * The gap has no suspension point, and the room launches its supersession rather than running it in
     * the `Farewell`'s handler, so a test dispatcher can schedule neither into it. The room's dispatcher
     * is therefore an [InlineSwitchDispatcher]: an ordinary test dispatcher until the hook — the room's
     * clock, read for the `HostLost` timestamp once the window is decided — switches it to run launches
     * in place, delivers the `Farewell`, and switches it back. The supersession then runs to its end on
     * the hook's stack, which is what a second thread would do inside the gap, and the dispatcher counts
     * the launches it ran in place, so a rig that ran none fails.
     */
    @Test
    fun `a supersession told once the window is decided lost leaves HostLost the only ending`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = ScriptedSeam(joinerId, hostId)
            val dispatcher = InlineSwitchDispatcher(StandardTestDispatcher(testScheduler))
            val roomScope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
            var roomRef: SeamRoom? = null
            var hostLostAtRig: Boolean? = null
            var inlineLaunches = 0
            var events: List<MembershipEvent> = emptyList()
            val (room, token) = admittedJoiner(seam, scope = roomScope) {
                val r = roomRef
                // Decided (no job) but not yet torn down (the flight is still pending): only the gap.
                if (r != null && hostLostAtRig == null && r.hasPendingResume() && !r.hasReconnectInFlight()) {
                    hostLostAtRig = events.any { it is MembershipEvent.HostLost }
                    dispatcher.inline = true
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        seam.deliverNow(AdmitMessage.Farewell(joinerId.value, supersededBy = "joiner-2"))
                    }
                    dispatcher.inline = false
                    inlineLaunches = dispatcher.inlineLaunches
                }
            }
            roomRef = room
            events = recordEvents(room)

            expireWindow(room, seam, token)
            runCurrent()

            val endings = events.filter {
                it is MembershipEvent.HostLost || (it as? MembershipEvent.Left)?.reason is LeaveReason.Superseded
            }
            assertAll(
                { assertEquals(false, hostLostAtRig, "precondition: the rig fired between the verdict and HostLost") },
                { assertTrue(inlineLaunches > 0, "precondition: the Farewell's handler ran the room's launch in place") },
                { assertEquals(1, endings.size, "one ending, not two (events: $events)") },
                { assertIs<MembershipEvent.HostLost>(endings.firstOrNull(), "the window's verdict took the lock first") },
            )
        }

    /**
     * Episode one: the host introduces itself (so the joiner monitors it, and a later drop reaches the
     * room through that detector), the link tears, the joiner says Hello again, the host challenges it,
     * and its restored `Welcome` closes the episode.
     */
    private suspend fun TestScope.closeEpisodeOne(room: SeamRoom, seam: ScriptedSeam, events: List<MembershipEvent>) {
        seam.deliver(AdmitMessage.Welcome(assignedPeerId = hostId.value, displayName = "host", sessionId = hostId.value, key = TEST_KEY_B))
        runCurrent()
        seam.tear()
        runCurrent()
        challengeReHello(seam)
        seam.deliver(restoredWelcome)
        runCurrent()
        assertAll(
            { assertEquals(1, seam.resumesSent, "precondition: episode one said Hello once") },
            { assertEquals(1, events.count { it is MembershipEvent.Resumed }, "precondition: episode one was restored (events: $events)") },
            { assertFalse(room.hasReconnectInFlight(), "precondition: episode one ended") },
            { assertTrue(room.hasDetector(hostId), "precondition: episode one restored the host detector") },
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
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.deliverNow(restoredWelcome) }
        return AckRig(pendingBefore = before, pendingAfter = room.hasPendingResume(), hostLostAnnounced = announced)
    }

    /**
     * A joiner the scripted host has admitted, holding a resume token. [onClock] runs on every clock
     * read, [onReweave] at the start of every re-weave, and [scope] parents the room.
     */
    private suspend fun TestScope.admittedJoiner(
        seam: ScriptedSeam,
        scope: CoroutineScope = backgroundScope,
        onReweave: suspend () -> Unit = {},
        onClock: () -> Unit = {},
    ): Pair<SeamRoom, ResumeToken> {
        val room = SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            memberName = "joiner",
            scope = scope,
            clock = {
                onClock()
                Instant.fromEpochMilliseconds(testScheduler.currentTime)
            },
            heartbeatConfig = config,
            roomId = null,
            device = RoomDevice.of(fakeKeyStore(0)),
            reweave = {
                onReweave()
                seam.heal()
                seam
            },
        ).also { it.start() }
        runCurrent()

        // The host proves its key, as every host does before its first Welcome (#3211).
        seam.deliver(handChallenge(assertNotNull(seam.hello, "precondition: the joiner said Hello"), joinerId, hostId))
        runCurrent()
        // The host admits the joiner: its own Welcome identifies the host and mints the token.
        seam.deliver(
            AdmitMessage.Welcome(
                assignedPeerId = joinerId.value,
                displayName = "joiner",
                sessionId = joinerId.value,
                key = TEST_KEY_B,
                roomId = "room-1",
                resumeSecret = ResumeSecret.generate(),
            ),
        )
        runCurrent()
        return room to assertNotNull(room.resumeToken, "precondition: the joiner holds a resume token")
    }

    /**
     * Tears the link, lets the joiner say Hello again and answer the host's challenge — so its own
     * `Welcome` would close the reconnect — but has the host send no `Welcome`, joins that flight with a
     * caller's own `resume()`, and runs the clock past the window. Returns what that caller got.
     */
    private suspend fun TestScope.expireWindow(
        room: SeamRoom,
        seam: ScriptedSeam,
        token: ResumeToken,
    ): ResumeResult.JoinerOutcome {
        seam.tear()
        runCurrent()
        challengeReHello(seam)
        assertAll(
            { assertEquals(1, seam.resumesSent, "precondition: the re-Hello went out") },
            { assertTrue(room.hasPendingResume(), "precondition: its verdict has not come back") },
        )
        val joined: Deferred<ResumeResult.JoinerOutcome> = async { room.resume(token) }
        runCurrent()

        advanceTimeBy(config.reconnectWindow.inWholeMilliseconds + 1)
        runCurrent()
        return joined.await()
    }

    /** The host challenges the joiner's latest re-Hello, which the joiner answers: its Welcome would now count. */
    private suspend fun TestScope.challengeReHello(seam: ScriptedSeam) {
        val hello = assertNotNull(seam.hello, "precondition: the joiner said Hello again")
        seam.deliver(handChallenge(hello, joinerId, hostId))
        runCurrent()
        assertTrue(seam.proofsSent > 0, "precondition: the joiner answered the host's challenge")
    }

    /** The host's Welcome to the joiner after a re-Hello that found its seat held: what a `ResumeAck` was. */
    private val restoredWelcome = AdmitMessage.Welcome(
        assignedPeerId = joinerId.value,
        displayName = "joiner",
        sessionId = joinerId.value,
        key = TEST_KEY_B,
        roomId = "room-1",
        returnKind = ReturnKind.Restored,
    )
}

/**
 * A joiner's seam that the test plays the host on. It starts `Woven` with the host listed, tears on
 * [tear], and is healed in place by the room's re-weave, which also lists a host [dropHost] took out
 * of [peers]. It counts the `Resume` frames sent to the host, and runs [onStateCollectionEnded] as
 * each collection of [state] ends, saying whether the collecting coroutine was cancelled.
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
    private val _peers = MutableStateFlow(setOf(selfId, hostId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Bounded, like every delivery buffer here: the test queues one frame.
    private val frames = Channel<Swatch>(capacity = 8)
    private var collector: FlowCollector<Swatch>? = null

    override val incoming: Flow<Swatch> = object : Flow<Swatch> {
        override suspend fun collect(collector: FlowCollector<Swatch>) {
            this@ScriptedSeam.collector = collector
            for (frame in frames) collector.emit(frame)
        }
    }

    var proofsSent: Int = 0
        private set

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

    /**
     * Takes the host out of [peers], as a fabric whose link to it closed does; [heal] lists it again
     * unless [healListsHost] is off, and then only [listHost] does.
     */
    fun dropHost() {
        _peers.value = setOf(selfId)
    }

    var healListsHost: Boolean = true

    fun heal() {
        if (healListsHost) listHost()
        _state.value = SeamState.Woven
    }

    fun listHost() {
        _peers.value = setOf(selfId, hostId)
    }

    /** The joiner's latest Hello, which a hand-built challenge must echo (#3211). */
    var hello: AdmitMessage.Hello? = null
        private set

    override suspend fun broadcast(payload: ByteArray) {
        (AdmitMessage.decode(payload) as? AdmitMessage.Hello)?.let { hello = it }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        val decoded = AdmitMessage.decode(payload)
        if (peer == hostId && decoded is AdmitMessage.Proof) proofsSent++
        val hello = decoded as? AdmitMessage.Hello ?: return
        if (peer == hostId) {
            this.hello = hello
            resumesSent++
        }
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

/**
 * [delegate], with a switch: while [inline] is on, a launch or resumption runs in place on the caller's
 * stack instead of being queued, and [inlineLaunches] counts each one. Its delays and timeouts are
 * [delegate]'s, so the room stays on virtual time.
 *
 * A dispatcher that never needs to dispatch runs work in place only when no unconfined event loop is
 * already running on the thread; the room's own coroutines are queued while the switch is off, so the
 * hook that turns it on runs outside one.
 */
@OptIn(InternalCoroutinesApi::class)
private class InlineSwitchDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
    var inline: Boolean = false
    var inlineLaunches: Int = 0
        private set

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        if (inline) inlineLaunches++
        return !inline
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
}
