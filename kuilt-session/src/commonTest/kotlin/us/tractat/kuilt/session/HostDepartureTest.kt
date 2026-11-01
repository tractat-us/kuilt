@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.election.ElectionOutcome
import us.tractat.kuilt.session.election.SeamElectionLobby
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A host that leaves on purpose says so (#2670): it fans out a `Farewell` naming itself, and a joiner
 * goes straight to `HostLost(HostLeft)` instead of showing a live room for the whole reconnect window.
 *
 * **What this file can and cannot prove.** Everything here runs over seams that cannot lose a frame on
 * their own, so it pins the *room's reaction* and the wait inside `leave`, never the transport's
 * *delivery*. Delivery lives in `NwHostDepartureRoomTest` and `NwHostDepartureLoopbackTest` (`:kuilt-nw`)
 * and `WebSocketHostDepartureTest` (`:kuilt-websocket`), and on real iPhones after that.
 */
class HostDepartureTest {

    private companion object {
        /**
         * `AdmitMessage.encode(Farewell("host"))`, captured by running the encoder at `f83d01b43`, before
         * #2670 touched anything. A literal, so a codec change cannot move both sides of the comparison.
         * Reading it: the `0x61` admit prefix, the `farewell` type name, `peerId` = `host`, and no `expired`
         * key, since a default is not encoded.
         */
        const val FAREWELL_NAMING_HOST = "619f686661726577656c6cbf6670656572496464686f7374ffff"
    }

    private val config = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 5.seconds,
    )

    private fun TestScope.clock(): () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    /** Every event [room] emits from here on, subscribed before anything can be emitted behind it. */
    private fun TestScope.recordEvents(room: Room): MutableList<MembershipEvent> {
        val seen = mutableListOf<MembershipEvent>()
        backgroundScope.launch { room.events.collect { seen += it } }
        testScheduler.runCurrent()
        return seen
    }

    private fun List<MembershipEvent>.hostLost() = filterIsInstance<MembershipEvent.HostLost>()

    private fun List<MembershipEvent>.openedAWindow() = any { it is MembershipEvent.WindowOpened }

    /** Delivers everything except the host's own `Farewell` naming itself, which it counts and drops. */
    private class FarewellDroppingSeam(private val delegate: Seam) : Seam by delegate {
        var dropped = 0
            private set

        private fun isHostFarewell(payload: ByteArray): Boolean {
            val message = AdmitMessage.decode(payload) as? AdmitMessage.Farewell ?: return false
            return message.peerId == selfId.value
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (isHostFarewell(payload)) dropped += 1 else delegate.sendTo(peer, payload)
        }

        override suspend fun broadcast(payload: ByteArray) {
            if (isHostFarewell(payload)) dropped += 1 else delegate.broadcast(payload)
        }
    }

    /** Everything the host addressed to a peer, in order. */
    private class RecordingSeam(private val delegate: Seam) : Seam by delegate {
        val sent = mutableListOf<Pair<PeerId, ByteArray>>()

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            sent += peer to payload
            delegate.sendTo(peer, payload)
        }
    }

    /** Parks every addressed send to [wedged] forever: the #1655 black hole, for one recipient only. */
    private class WedgedRecipientSeam(private val delegate: Seam) : Seam by delegate {
        var wedged: PeerId? = null
        val parked = CompletableDeferred<Unit>()

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (peer == wedged) {
                parked.complete(Unit)
                awaitCancellation()
            }
            delegate.sendTo(peer, payload)
        }
    }

    // ── The property ──────────────────────────────────────────────────────────

    @Test
    fun aHostsLeaveEndsTheJoinersRoomWithoutOpeningAWindow() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), config)
            val host = factory.host(Pattern("Host"))
            val joiner = factory.join(InMemoryTag("Joiner"))
            joiner.roster.first { it.isNotEmpty() }
            val events = recordEvents(joiner)

            host.leave()
            testScheduler.runCurrent()
            // Read before the clock moves on: the window is still far off, so only the frame can have
            // ended the room by now.
            val endedByTheTimeLeaveReturned = events.hostLost().map { it.reason }

            // Run well past the window: a window opened behind the terminal event would surface here.
            testScheduler.advanceTimeBy(config.reconnectWindow * 2)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), endedByTheTimeLeaveReturned) },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), events.hostLost().map { it.reason }) },
                { assertTrue(!events.openedAWindow(), "no window may open on a deliberate departure: $events") },
                { assertTrue(events.none { it is MembershipEvent.Partitioned }, "nor a Partitioned: $events") },
            )
        }

    @Test
    fun everyJoinerInARoomOfThreeLearnsIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), config)
            val host = factory.host(Pattern("Host"))
            val first = factory.join(InMemoryTag("First"))
            val second = factory.join(InMemoryTag("Second"))
            host.roster.first { it.size == 2 }
            first.roster.first { it.size == 2 }
            second.roster.first { it.size == 2 }
            val firstEvents = recordEvents(first)
            val secondEvents = recordEvents(second)

            host.leave()
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), firstEvents.hostLost().map { it.reason }) },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), secondEvents.hostLost().map { it.reason }) },
            )
        }

    /**
     * The room a lobby hands over is what a consumer calls its lobby once `start()` has run, and its
     * `reweave = { seam }` is exactly what opens a window on a tear. Before an adoption, a host leaving
     * is already prompt: `LobbyPromotionTest` pins `BecameHost` and `Torn` off the roster drop.
     */
    @Test
    fun aRoomAdoptedFromAnElectionLobbyEndsTheSameWay() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock(), config)
            val a = loom.weave(Rendezvous.New(Pattern("g")))
            val b = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            fun lobbyOver(seam: Seam) =
                SeamElectionLobby(seam = seam, factory = factory, scope = backgroundScope, clock = clock(), roomKey = null)
            val lobbyA = lobbyOver(a)
            val lobbyB = lobbyOver(b)
            val elected = lobbyA.host.first { lobbyA.peers.value.size == 2 }
            lobbyB.host.first { lobbyB.peers.value.size == 2 }
            val hostLobby = if (lobbyA.selfId == elected) lobbyA else lobbyB
            val memberLobby = if (lobbyA.selfId == elected) lobbyB else lobbyA

            val member = async { memberLobby.awaitRoom(memberName = "Member") }
            val hostRoom = hostLobby.start(memberName = "Host")
            val memberRoom = assertIs<ElectionOutcome.Adopted>(member.await()).room
            memberRoom.roster.first { it.isNotEmpty() }
            val events = recordEvents(memberRoom)

            hostRoom.leave()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(config.reconnectWindow * 2)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), events.hostLost().map { it.reason }) },
                { assertTrue(!events.openedAWindow(), "no window in an adopted room either: $events") },
            )
        }

    // ── The fallback ──────────────────────────────────────────────────────────

    @Test
    fun aLostFarewellFallsBackToTodaysWindow() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock(), config)
            val hostSeam = FarewellDroppingSeam(loom.host(Pattern("Host")))
            val host = factory.adopt(hostSeam, SessionRole.Host)
            val joiner = factory.join(InMemoryTag("Host"))
            joiner.roster.first { it.isNotEmpty() }
            val events = recordEvents(joiner)
            val before = testScheduler.currentTime

            host.leave()
            testScheduler.runCurrent()
            val droppedInLeave = hostSeam.dropped
            testScheduler.advanceTimeBy(config.reconnectWindow * 2)
            testScheduler.runCurrent()
            val elapsed = events.hostLost().single().at.toEpochMilliseconds() - before

            assertAll(
                // The rig fired, or this is the "no Farewell was ever sent" world and proves nothing.
                { assertEquals(1, droppedInLeave, "the rig must have eaten exactly the one Farewell") },
                { assertTrue(events.openedAWindow(), "the joiner opens today's window on the tear: $events") },
                { assertNotEquals<FailureReason>(FailureReason.HostLeft, events.hostLost().single().reason) },
                {
                    assertTrue(
                        elapsed >= config.reconnectWindow.inWholeMilliseconds,
                        "with no Farewell the joiner waits out the window, took ${elapsed}ms",
                    )
                },
            )
        }

    /**
     * The `Farewell` can lose the race to its own link: the joiner has already seen the tear and opened
     * a window, and the frame arrives while that window is open. It ends the window rather than being
     * dropped, because it is the better answer to the question the window is asking.
     */
    @Test
    fun aFarewellArrivingAfterTheTearCutsTheOpenWindowShort() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("joiner")
            val hostId = PeerId("host")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId))
            // Same-instance heal: the self-healing mesh a lobby adopts, whose window stays open while
            // Resume goes unanswered.
            val joiner = SeamRoom(
                seam = seam,
                role = SessionRole.Joiner,
                memberName = "Joiner",
                scope = backgroundScope,
                clock = clock(),
                heartbeatConfig = config,
                reweave = { seam },
            ).also { it.start() }
            val events = recordEvents(joiner)
            seam.deliver(
                hostId,
                AdmitMessage.encode(AdmitMessage.Welcome(hostId.value, "Host", hostId.value, roomId = "room")),
            )
            seam.deliver(
                hostId,
                AdmitMessage.encode(AdmitMessage.Welcome(self.value, "Joiner", self.value, roomId = "room")),
            )
            testScheduler.runCurrent()
            assertEquals(hostId, joiner.roster.value.single().id, "the joiner must be admitted first")

            seam.removePeer(hostId)
            testScheduler.runCurrent() // a StateFlow conflates: the detector has to see the drop
            seam.addPeer(hostId) // the mesh healed, so Resume has a route out and the host's frame a route in
            testScheduler.advanceTimeBy(config.interval)
            testScheduler.runCurrent()
            val windowOpenAtFarewell = events.openedAWindow()

            seam.deliver(hostId, AdmitMessage.encode(AdmitMessage.Farewell(hostId.value)))
            testScheduler.runCurrent()
            // Virtual time has not moved since the frame landed, so this is the reason at that instant:
            // a HostLeft held back to the window's deadline is absent here and present below.
            val endedAtOnce = events.hostLost().map { it.reason }
            testScheduler.advanceTimeBy(config.reconnectWindow * 2)
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(windowOpenAtFarewell, "rig: the window must be open when the frame lands: $events") },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), endedAtOnce, "at once, not at the deadline") },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), events.hostLost().map { it.reason }) },
            )
        }

    // ── Who may say it ────────────────────────────────────────────────────────

    /** The counterpart to [aHostsLeaveEndsTheJoinersRoomWithoutOpeningAWindow]: same frame, wrong sender. */
    @Test
    fun aFarewellNamingTheHostFromAnyoneElseIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val self = PeerId("joiner")
            val hostId = PeerId("host")
            val forger = PeerId("forger")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId, forger))
            val joiner = SeamRoom(
                seam = seam,
                role = SessionRole.Joiner,
                memberName = "Joiner",
                scope = backgroundScope,
                clock = clock(),
                heartbeatConfig = config,
            ).also { it.start() }
            val events = recordEvents(joiner)
            seam.deliver(
                hostId,
                AdmitMessage.encode(AdmitMessage.Welcome(hostId.value, "Host", hostId.value, roomId = "room")),
            )
            seam.deliver(
                hostId,
                AdmitMessage.encode(AdmitMessage.Welcome(self.value, "Joiner", self.value, roomId = "room")),
            )
            testScheduler.runCurrent()
            val frame = AdmitMessage.encode(AdmitMessage.Farewell(hostId.value))

            seam.deliver(forger, frame)
            testScheduler.runCurrent()
            val afterForgery = events.hostLost()
            val stillOpen = seam.state.value !is SeamState.Torn

            // Positive control: the identical bytes from the host itself do end the room, so the
            // refusal above is about the sender and not about a frame that was never going to act.
            if (stillOpen) seam.deliver(hostId, frame) // a torn seam refuses delivery; the assertions name why
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(emptyList(), afterForgery, "a forged Farewell must not end the room") },
                { assertTrue(stillOpen, "and must not tear the seam") },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), events.hostLost().map { it.reason }) },
            )
        }

    /**
     * What keeps an older joiner safe: the frame is an ordinary `Farewell` on the wire, a shape every
     * shipped joiner decodes. Its `handleFarewell` then returned on `departed == host`, and that gate is
     * what this PR removes, so it can only be read rather than run here (`f83d01b43`,
     * `SeamRoom.handleFarewell`). This pins the half that is still checkable: the bytes a host really
     * sends, against [FAREWELL_NAMING_HOST], which was captured from the codec before this frame existed.
     */
    @Test
    fun theFrameIsAnOrdinaryFarewellOnTheWire() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val hostId = PeerId("host")
            val joinerId = PeerId("joiner")
            val seam = FakeSeam(selfId = hostId, initialPeers = setOf(hostId, joinerId))
            val host = SeamRoom(
                seam = seam,
                role = SessionRole.Host,
                memberName = "Host",
                scope = backgroundScope,
                clock = clock(),
                heartbeatConfig = config,
            ).also { it.start() }
            seam.deliver(joinerId, AdmitMessage.encode(AdmitMessage.Hello("Joiner", "session")))
            testScheduler.runCurrent()
            assertEquals(joinerId, host.roster.value.single().id, "rig: the joiner must be admitted first")

            host.leave()
            testScheduler.runCurrent()

            val farewell = seam.directed
                .filter { (to, _) -> to == joinerId }
                .map { (_, payload) -> payload }
                .single { AdmitMessage.decode(it) is AdmitMessage.Farewell }
            assertAll(
                { assertEquals(AdmitMessage.Farewell(hostId.value, expired = false), AdmitMessage.decode(farewell)) },
                { assertEquals(FAREWELL_NAMING_HOST, farewell.toHexString()) },
            )
        }

    // ── The wait inside leave ─────────────────────────────────────────────────

    @Test
    fun aHostWithNobodyToTellLeavesAtOnce() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), config)
            val host = factory.host(Pattern("Host"))
            val before = testScheduler.currentTime

            host.leave()

            assertEquals(before, testScheduler.currentTime, "an empty room has nothing to wait for")
        }

    /** A joiner leaving must not pick up the host's wait, and its own `Goodbye` path is unchanged. */
    @Test
    fun aJoinersLeaveTakesNoLonger() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), config)
            factory.host(Pattern("Host"))
            val joiner = factory.join(InMemoryTag("Joiner"))
            joiner.roster.first { it.isNotEmpty() }
            val before = testScheduler.currentTime

            joiner.leave()

            assertEquals(before, testScheduler.currentTime)
        }

    /** `LeaveReason.Error` is not a decision to end the room, so the ruling's frame does not go out. */
    @Test
    fun onlyACleanLeaveIsAnnounced() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock(), config)
            val hostSeam = FarewellDroppingSeam(loom.host(Pattern("Host")))
            val host = factory.adopt(hostSeam, SessionRole.Host)
            val joiner = factory.join(InMemoryTag("Host"))
            joiner.roster.first { it.isNotEmpty() }

            host.leave(LeaveReason.Error("boom"))
            testScheduler.runCurrent()

            assertEquals(0, hostSeam.dropped, "no Farewell left the host at all")
        }

    /**
     * One wedged recipient must not hold the host's leave open or stop the others hearing, which is what
     * a fan-out per recipient is for. `fanOutSendBudget` is 5.2 s with this [config] (window plus timeout),
     * so a leave that waited for its lanes to drain would show it.
     */
    @Test
    fun aWedgedRecipientHoldsUpNeitherTheLeaveNorItsNeighbours() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock(), config)
            val base = loom.host(Pattern("Host"))
            val wedged = factory.join(InMemoryTag("Host"))
            val healthy = factory.join(InMemoryTag("Host"))
            val hostSeam = WedgedRecipientSeam(base)
            val host = factory.adopt(hostSeam, SessionRole.Host)
            healthy.roster.first { it.isNotEmpty() }
            host.roster.first { it.size == 2 }
            val events = recordEvents(healthy)
            hostSeam.wedged = wedged.selfId
            val before = testScheduler.currentTime

            host.leave()
            val elapsed = testScheduler.currentTime - before

            assertAll(
                { assertTrue(hostSeam.parked.isCompleted, "rig: the writer really did park in sendTo") },
                { assertEquals(HOST_FAREWELL_HANDOFF.inWholeMilliseconds, elapsed, "bounded by the hand-off wait") },
                { assertIs<SeamState.Torn>(base.state.value, "and the seam still closed") },
                { assertEquals(listOf<FailureReason>(FailureReason.HostLeft), events.hostLost().map { it.reason }) },
            )
        }

    /** Cancelling the caller inside the wait must not strand an open seam behind a closed room. */
    @Test
    fun aCancelledLeaveStillClosesTheSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock(), config)
            val hostSeam = loom.host(Pattern("Host"))
            val host = factory.adopt(hostSeam, SessionRole.Host)
            val joiner = factory.join(InMemoryTag("Host"))
            joiner.roster.first { it.isNotEmpty() }
            host.roster.first { it.isNotEmpty() }

            val leaving = launchLeave(host)
            testScheduler.advanceTimeBy(HOST_FAREWELL_HANDOFF / 2)
            testScheduler.runCurrent()
            val wasWaiting = leaving.isActive

            leaving.cancelAndJoin()

            assertAll(
                { assertTrue(wasWaiting, "rig: the cancel must land inside the wait") },
                { assertIs<SeamState.Torn>(hostSeam.state.value, "the seam closes regardless") },
            )
        }

    private fun CoroutineScope.launchLeave(room: Room) = launch { room.leave() }
}
