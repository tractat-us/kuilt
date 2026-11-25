@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.test.FlakyLifecycleLoom
import us.tractat.kuilt.test.FlakyLifecycleSeam
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
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
            is Rendezvous.New -> rv.pattern.sessionName
            is Rendezvous.Existing -> rv.tag.sessionName
        }
    }

    @Test
    fun `joiner auto-resumes over a resumable base within the reconnect window`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
                memberName = "table-7",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(0)),
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
                admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
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
            val joinerRoom = SeamRoom(
                seam = muxClient.join(tag),
                role = SessionRole.Joiner,
                memberName = "client",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(1)),
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { muxClient.join(tag) },
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(joinerRoom.roomId.value, "joiner must be admitted")

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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
                memberName = "table-7",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(2)),
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
                admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
            ).also { it.start() }

            val clientId = PeerId("client")
            var seed = 1
            // Base weaves once (admit) then fails every re-weave — the window can never heal.
            val base = object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    if (seed > 1) throw RuntimeException("base weave fails on reconnect")
                    val (serverConn, clientConn) = connectionPair()
                    source.offer(serverConn)
                    return hubMesh(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
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
                device = RoomDevice.of(fakeKeyStore(3)),
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { muxClient.join(tag) },
            ).also { it.start() }

            joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(joinerRoom.roomId.value)

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            muxClient.closeBase()

            // Advance PAST the 500 ms window with margin.
            repeat(9) { clockMs += 100L; advanceTimeBy(100L) }

            val event = hostLost.await()
            assertIs<MembershipEvent.HostLost>(event)
            assertEquals(FailureReason.WindowExpired, event.reason)

            // Terminal after HostLost — a post-hoc resume is rejected.
            assertIs<ResumeResult.WindowClosed>(joinerRoom.sayHelloAgain())
        }

    @Test
    fun `a reconnect after the host's window closed is readmitted rather than HostLost`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }

            // Host gets a SHORT reconnect window so, by the time the joiner re-weaves and says Hello
            // again, the host has let the seat go. Before #3159 Task 1.7 the joiner's `Resume` was then
            // refused terminally and the room ended HostLost(Refused). A Hello is admitted afresh
            // instead, and the room keeps that admission (Amendment C): no HostLost at all.
            val hostConfig = HeartbeatConfig(
                interval = 100.milliseconds,
                timeout = 200.milliseconds,
                reconnectWindow = 100.milliseconds,
            )

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
                memberName = "table-7",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(4)),
                heartbeatConfig = hostConfig,
                roomId = RoomId("room-1"),
                admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
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
            val joinerRoom = SeamRoom(
                seam = muxClient.join(tag),
                role = SessionRole.Joiner,
                memberName = "client",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(5)),
                heartbeatConfig = fastConfig,
                roomId = null,
                // Delay the re-weave past the host's 100 ms window so the resume lands after the
                // host window has closed (persistent WindowClosed reject).
                // Timing: host reconnectWindow (100 ms) has already closed when this delay(250) fires,
                // so the host has no open window when the Resume lands → persistent WindowClosed → Reject.
                reweave = { delay(250L); muxClient.join(tag) },
            ).also { it.start() }

            joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(joinerRoom.roomId.value, "joiner must be admitted")

            val joinerLog = EventLog(joinerRoom, backgroundScope)
            val hostLog = EventLog(hostRoom, backgroundScope)
            val hostMark = hostLog.mark()

            muxClient.closeBase()

            // Advance PAST the joiner's 500 ms window with margin, keeping the injected clock in
            // step so the reconnect deadline is reached.
            repeat(9) { clockMs += 100L; advanceTimeBy(100L) }

            val hostEdges = hostLog.edgesSince(hostMark)
            assertAll(
                { assertIs<MembershipEvent.Left>(hostEdges.firstOrNull(), "rig: the host let the seat go: ${hostEdges.joinToString { it.describe() }}") },
                { assertEquals(listOf(joinerRoom.selfId), hostEdges.filterIsInstance<MembershipEvent.Joined>().map { it.member.id }, "readmitted once") },
                { assertTrue(joinerLog.since(0).none { it is MembershipEvent.HostLost }, joinerLog.since(0).joinToString { it.describe() }) },
                { assertTrue(joinerRoom.roster.value.any { it.liveness == Liveness.Connected }, "the joiner is in session with its host") },
            )
        }

    @Test
    fun `joiner torn before admit goes straight to HostLost`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

            // A joiner with no host to admit it: it never mints a resume token, so a tear must
            // go straight to terminal HostLost, never holding the reconnect window open.
            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val tag = InMemoryTag("joiner")
            val joinerSeam = loom.join(tag)
            val joinerRoom = SeamRoom(
                seam = joinerSeam,
                role = SessionRole.Joiner,
                memberName = "joiner",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(6)),
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { loom.join(tag) },
            ).also { it.start() }

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
            assertNull(joinerRoom.roomId.value, "joiner torn before admit has no room id")

            // Tear the joiner's transport. HostLost must fire with NO time advancement — the
            // no-token path is immediate, it does not wait out the reconnect window.
            joinerSeam.tear()
            runCurrent()
            assertTrue(
                hostLost.isCompleted,
                "with no host the joiner must go HostLost immediately, not hold the window",
            )
            assertIs<MembershipEvent.HostLost>(hostLost.await())
        }

    @Test
    fun `joiner torn before admit reports HostLost Unrecoverable`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

            // A joiner with no host to admit it: it never mints a resume token, so a tear takes the
            // immediate-terminal branch of runReconnect (no token / no host / no reweave target) and
            // the HostLost is classified Unrecoverable — there was never a resume path.
            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val tag = InMemoryTag("joiner")
            val joinerSeam = loom.join(tag)
            val joinerRoom = SeamRoom(
                seam = joinerSeam,
                role = SessionRole.Joiner,
                memberName = "joiner",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(7)),
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { loom.join(tag) },
            ).also { it.start() }

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
            assertNull(joinerRoom.roomId.value, "joiner torn before admit has no room id")

            joinerSeam.tear()
            runCurrent()
            val event = hostLost.await()
            assertIs<MembershipEvent.HostLost>(event)
            assertEquals(FailureReason.Unrecoverable, event.reason)
        }

    @Test
    fun `joiner auto-resumes on repeated in-session tears`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val h = reconnectHarness(clock)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.roomId.value, "joiner must be admitted")

            val resumes = mutableListOf<MembershipEvent.Resumed>()
            backgroundScope.launch {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().collect { resumes += it }
            }
            val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // Episode 1 — first tear auto-resumes (the torn watcher drives it).
            h.muxClient.closeBase()
            repeat(4) { advanceTimeBy(100L) }
            assertEquals(1, resumes.size, "first in-session tear must auto-resume")

            // Episode 2 — a SECOND tear must also auto-resume. The torn watcher is single-shot, so
            // this proves the guard is cleared on success AND the host-liveness detector restarted:
            // the restarted detector's TransportClosed re-triggers the reconnect (lobby reconnect).
            h.muxClient.closeBase()
            repeat(8) { advanceTimeBy(100L) }
            assertEquals(2, resumes.size, "a second in-session tear must auto-resume again")
            assertFalse(hostLost.isCompleted, "repeated auto-resume must not fall to HostLost")
            hostLost.cancel()
        }

    @Test
    fun `the host-liveness detector is silenced during a reconnect and restarted on success`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // The host detector runs on the SAME reconnectWindow budget as the reconnect. If it
            // keeps running during the reconnect it drives host-liveness on a DIFFERENT coroutine,
            // so under a multi-threaded dispatcher its PeerLost can race an in-flight resume into a
            // contradictory HostLost + Resumed. The reconnect must be authoritative: the detector is
            // stopped for its duration and restarted only on success (so the resumed room is not
            // left unmonitored). This gates the re-weave to hold the reconnect in-flight and asserts
            // the detector's lifecycle directly — the multi-threaded race has no deterministic
            // single-threaded event footprint, so we test the mechanism, not a downstream symptom.
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val reweaveGate = CompletableDeferred<Unit>()
            val h = reconnectHarness(clock, reweaveGate = reweaveGate)

            val hostId = h.hostRoom.selfId
            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.roomId.value)
            assertTrue(h.joinerRoom.hasDetector(hostId), "host detector runs while connected")

            val resumed = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
            val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // Tear, then let the reconnect start and block on the gated re-weave.
            h.muxClient.closeBase()
            repeat(2) { advanceTimeBy(100L) }

            // Mid-reconnect: the detector MUST be stopped so it cannot decide host-liveness while the
            // reconnect owns that decision. (Without the fix it is still registered here.)
            assertFalse(
                h.joinerRoom.hasDetector(hostId),
                "the host detector must be silenced for the duration of the reconnect",
            )

            // Release the re-weave; the resume completes.
            reweaveGate.complete(Unit)
            repeat(4) { advanceTimeBy(100L) }

            assertIs<MembershipEvent.Resumed>(resumed.await())
            assertTrue(
                h.joinerRoom.hasDetector(hostId),
                "host-liveness monitoring must be restarted after a successful resume",
            )
            assertFalse(hostLost.isCompleted, "a silenced+restarted detector must not fire HostLost")
            hostLost.cancel()
        }

    /**
     * A loss the silenced host detector reported, handed over only after the resume restarted
     * another (#3609). Silencing it is meant to leave host-liveness to the reconnect; a collector that
     * lost its thread between taking the `PeerLost` and the room's lock defeats that unless the loss
     * is checked against the detector now registered. Acted on, it ends a healed room as host-lost
     * and stops the detector that is watching it. `StaleDetectorEventTest` says why the event is
     * handed over by [SeamRoom.handlePartitionEventForTest]; the control is the same loss from the
     * restarted detector.
     */
    @Test
    fun `a loss from the host detector a resume replaced does not end the room`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val (h, old, current) = resumedOnce()
            val hostId = h.hostRoom.selfId
            val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
            runCurrent()

            h.joinerRoom.handlePartitionEventForTest(PartitionEvent.PeerLost(hostId, Instant.fromEpochMilliseconds(0L)), old)
            runCurrent()
            val staleHostLost = hostLost.isCompleted
            val staleWatched = h.joinerRoom.detectorJobForTest(hostId) === current

            h.joinerRoom.handlePartitionEventForTest(PartitionEvent.PeerLost(hostId, Instant.fromEpochMilliseconds(0L)), current)
            runCurrent()

            assertAll(
                { assertFalse(staleHostLost, "a stale loss does not end the room") },
                { assertTrue(staleWatched, "and leaves the restarted detector watching the host") },
                { assertTrue(hostLost.isCompleted, "control: the same loss from the restarted detector ends it") },
            )
            hostLost.cancel()
        }

    /**
     * The host-resume fork of the same gap: a transport close the silenced detector reported, handed
     * over after the resume. Acted on, it starts a second reconnect episode on the healed link.
     */
    @Test
    fun `a transport close from the host detector a resume replaced starts no reconnect`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val (h, old, current) = resumedOnce()
            val hostId = h.hostRoom.selfId
            val closed = PartitionEvent.PeerUnresponsive(
                hostId,
                Instant.fromEpochMilliseconds(0L),
                PartitionEvent.Reason.TransportClosed,
            )
            val reweavesBefore = h.reweaveCount()

            h.joinerRoom.handlePartitionEventForTest(closed, old)
            val staleInFlight = h.joinerRoom.hasReconnectInFlight()
            runCurrent()
            val staleReweaves = h.reweaveCount()

            h.joinerRoom.handlePartitionEventForTest(closed, current)
            val controlInFlight = h.joinerRoom.hasReconnectInFlight()

            assertAll(
                { assertFalse(staleInFlight, "a stale transport close claims no reconnect") },
                { assertEquals(reweavesBefore, staleReweaves, "and re-weaves nothing") },
                { assertTrue(controlInFlight, "control: the same close from the restarted detector claims one") },
            )
        }

    private data class Resumed(val harness: ReconnectHarness, val old: Job, val current: Job)

    /** A joiner admitted, torn and resumed once: its host detector was silenced, and another restarted. */
    private suspend fun TestScope.resumedOnce(): Resumed {
        val h = reconnectHarness({ Instant.fromEpochMilliseconds(0L) })
        val hostId = h.hostRoom.selfId
        h.hostRoom.roster.first { it.size == 1 }
        h.joinerRoom.roster.first { it.isNotEmpty() }
        val old = assertNotNull(h.joinerRoom.detectorJobForTest(hostId), "rig: the host is watched while connected")
        val resumed = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
        h.muxClient.closeBase()
        repeat(4) { advanceTimeBy(100L) }
        assertIs<MembershipEvent.Resumed>(resumed.await(), "rig: the joiner resumed")
        val current = assertNotNull(h.joinerRoom.detectorJobForTest(hostId), "rig: the resume restarted a host detector")
        assertNotSame(old, current, "rig: a new one")
        assertTrue(old.isCancelled, "rig: the old one was silenced")
        assertFalse(h.joinerRoom.hasReconnectInFlight(), "rig: the episode is over")
        return Resumed(h, old, current)
    }

    @Test
    fun `leave during an active reconnect stops the loop`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // A leave() while a reconnect window is open must cancel the reconnect. Otherwise the
            // loop keeps re-weaving until the window elapses (each re-weave reopens the channel
            // leave() just closed) and then fires a spurious HostLost for a room that left cleanly.
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val reweaveGate = CompletableDeferred<Unit>()
            val h = reconnectHarness(clock, reweaveGate = reweaveGate)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.roomId.value)

            val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // Tear → the reconnect starts and blocks on the gated re-weave (one entry so far).
            h.muxClient.closeBase()
            repeat(2) { advanceTimeBy(100L) }
            assertEquals(1, h.reweaveCount(), "the reconnect is in-flight, blocked on the first re-weave")

            // Leave mid-window, then release the gate and advance well past the reconnect window.
            h.joinerRoom.leave(LeaveReason.Normal)
            reweaveGate.complete(Unit)
            repeat(8) { advanceTimeBy(100L) }

            // The reconnect loop must have stopped — no further re-weaves after leave() — and it must
            // NOT have fired HostLost (the room left cleanly, it was not lost).
            assertEquals(1, h.reweaveCount(), "leave() must stop the reconnect loop: no further re-weave")
            assertFalse(hostLost.isCompleted, "a clean leave() must not surface a spurious HostLost")
            hostLost.cancel()
        }

    @Test
    fun `a discarded re-wove seam over a non-conforming loom is closed`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // SeamRoomFactory.join always supplies a reweave, so a joiner can sit over a
            // NON-resumable loom whose join() mints a NEW, unrelated seam. On a tear the reconnect
            // re-weaves once, detects the original seam is still Torn (the same-instance-heal check),
            // and gives up — but the throwaway seam it wove is a live connection that must be closed,
            // not leaked.
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val loom = InMemoryLoom()
            val hostRoom = SeamRoom(
                seam = loom.host(Pattern("h")),
                role = SessionRole.Host,
                memberName = "h",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(8)),
                heartbeatConfig = fastConfig,
                roomId = RoomId("room-1"),
                admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
            ).also { it.start() }

            // The joiner's live seam — torn to trigger the reconnect.
            val joinerSeam = FlakyLifecycleSeam(loom.join(InMemoryTag("h")), backgroundScope)
            // The throwaway the non-conforming reweave hands back (does NOT heal joinerSeam).
            val throwaway = FlakyLifecycleSeam(InMemoryLoom().join(InMemoryTag("x")), backgroundScope)
            val joinerRoom = SeamRoom(
                seam = joinerSeam,
                role = SessionRole.Joiner,
                memberName = "j",
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(9)),
                heartbeatConfig = fastConfig,
                roomId = null,
                reweave = { throwaway },
            ).also { it.start() }

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(joinerRoom.roomId.value, "joiner must be admitted")

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
            assertFalse(throwaway.state.value is SeamState.Torn, "throwaway starts open")

            joinerSeam.tear()
            repeat(2) { advanceTimeBy(100L) }

            val hostLostEvent = hostLost.await() // non-conforming heal → terminal
            assertIs<MembershipEvent.HostLost>(hostLostEvent)
            assertEquals(FailureReason.Unrecoverable, hostLostEvent.reason)
            assertTrue(
                throwaway.state.value is SeamState.Torn,
                "the discarded re-wove seam must be closed, not leaked",
            )
        }

    // ── Harness ─────────────────────────────────────────────────────────────────

    private class ReconnectHarness(
        val hostRoom: SeamRoom,
        val joinerRoom: SeamRoom,
        val muxClient: MuxClientLoom,
        /** Number of times the joiner's `reweave` lambda has been entered (single-threaded VT). */
        val reweaveCount: () -> Int,
    )

    /**
     * Stands up a host [SeamRoom] over a [MuxServerLoom] hub and a joiner [SeamRoom] over a
     * [MuxClientLoom] whose base weaves a fresh mesh connection each time. [muxClient.closeBase]
     * tears the joiner's transport; the joiner heals via `reweave = { muxClient.join(tag) }`.
     *
     * [reweaveDelay] (virtual ms) makes the re-weave complete late in the window. [reweaveGate], if
     * given, holds the re-weave suspended until completed — used to observe the reconnect in-flight.
     */
    private suspend fun TestScope.reconnectHarness(
        clock: () -> Instant,
        reweaveDelay: Long = 0L,
        reweaveGate: CompletableDeferred<Unit>? = null,
    ): ReconnectHarness {
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
        val hostRoom = SeamRoom(
            seam = hostSeam,
            role = SessionRole.Host,
            memberName = "table-7",
            scope = backgroundScope,
            clock = clock,
            device = RoomDevice.of(fakeKeyStore(10)),
            heartbeatConfig = fastConfig,
            roomId = RoomId("room-1"),
            admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
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
        var reweaveCount = 0
        val joinerRoom = SeamRoom(
            seam = muxClient.join(tag),
            role = SessionRole.Joiner,
            memberName = "client",
            scope = backgroundScope,
            clock = clock,
            device = RoomDevice.of(fakeKeyStore(11)),
            heartbeatConfig = fastConfig,
            roomId = null,
            reweave = {
                reweaveCount++
                reweaveGate?.await()
                if (reweaveDelay > 0L) delay(reweaveDelay)
                muxClient.join(tag)
            },
        ).also { it.start() }

        return ReconnectHarness(hostRoom, joinerRoom, muxClient, reweaveCount = { reweaveCount })
    }
}
