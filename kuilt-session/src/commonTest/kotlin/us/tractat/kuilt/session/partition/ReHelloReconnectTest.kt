@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session.partition

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.AdmitTap
import us.tractat.kuilt.session.EventLog
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.ObservedHold
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoom
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.admit.ReturnKind
import us.tractat.kuilt.session.describe
import us.tractat.kuilt.session.virtualClock
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A joiner's reconnect says Hello again instead of sending `Resume` (#3159, Task 1.7).
 *
 * The reconnect loop, its back-off and its teardown are unchanged; only what it sends is. After a
 * re-weave the joiner sends a fresh Hello **to its host** (never a broadcast, which a foreign host on
 * a flat loom would answer), answers the Challenge, and the episode ends on the host's answer:
 * - `Welcome(returnKind = Restored)`: the seat came back;
 * - a `Welcome` without it: the seat was gone and this was a fresh admission, which the room keeps
 *   (Amendment C: a success, not `HostLost`);
 * - a terminal `Reject` from the host whose challenge this joiner answered: the refusal is surfaced
 *   with its code. A terminal `Reject` from anyone else is ignored, and a retryable one from anyone
 *   only schedules another Hello (Amendment C).
 *
 * Two harnesses. Most tests script the host by hand over a [FakeSeam] behind a [FlakyLifecycleSeam],
 * so each chooses the host's answer and counts every frame the joiner sends. Two run a real host,
 * where the claim is about the whole exchange: zero `Resume` frames on the wire.
 */
class ReHelloReconnectTest {
    private val hostId = PeerId("host")
    private val bob = PeerId("bob")
    private val eve = PeerId("eve")
    private val roomId = RoomId("room-1")
    private val hostDevice = fakeDeviceKey(0)

    private val joinerConfig = HeartbeatConfig(
        interval = TICK,
        timeout = 1.seconds,
        reconnectWindow = 4.seconds,
    )

    // ── A host scripted by hand ──────────────────────────────────────────────

    /**
     * A joiner room over `FlakyLifecycleSeam(FakeSeam)` whose remotes are [hostId] and a bystander
     * [eve], admitted by a host this class plays: the reweave heals the same seam in place.
     */
    private inner class Scripted(private val test: TestScope, val fake: FakeSeam, val link: FlakyLifecycleSeam, val room: Room) {
        val log = EventLog(room, test.backgroundScope)

        fun broadcastHellos(): List<AdmitMessage.Hello> =
            fake.broadcasts.mapNotNull { AdmitMessage.decode(it) }.filterIsInstance<AdmitMessage.Hello>()

        fun sentTo(peer: PeerId): List<AdmitMessage> = fake.directed.filter { it.first == peer }.mapNotNull { AdmitMessage.decode(it.second) }

        fun sentToHost(): List<AdmitMessage> = sentTo(hostId)

        fun hellosToHost(): List<AdmitMessage.Hello> = sentToHost().filterIsInstance<AdmitMessage.Hello>()

        fun allHellos(): Int = broadcastHellos().size + fake.directed.mapNotNull { AdmitMessage.decode(it.second) }.count { it is AdmitMessage.Hello }

        fun resumes(): Int = fake.directed.mapNotNull { AdmitMessage.decode(it.second) }.count { it is AdmitMessage.Resume }

        suspend fun from(sender: PeerId, message: AdmitMessage) {
            fake.deliver(sender, AdmitMessage.encode(message))
            test.testScheduler.runCurrent()
        }

        suspend fun fromHost(message: AdmitMessage) = from(hostId, message)

        /** Challenge [hello], as the host would, and assert the joiner answered it — the rig fired. */
        suspend fun challenge(hello: AdmitMessage.Hello) {
            val before = sentToHost().count { it is AdmitMessage.Proof }
            val signed = Challenge.sign(
                hostDevice, roomId.value, hostId.value, bob.value,
                assertNotNull(hello.joinerNonce), ByteArray(Challenge.NONCE_SIZE) { 3 },
            )
            fromHost(AdmitMessage.Challenge(signed.wire))
            assertEquals(before + 1, sentToHost().count { it is AdmitMessage.Proof }, "rig: the joiner answered the challenge")
        }

        /** The self Welcome and the host intro: the shape of an admission, marked [returnKind]. */
        suspend fun welcome(returnKind: ReturnKind?) {
            fromHost(
                AdmitMessage.Welcome(
                    assignedPeerId = bob.value,
                    displayName = "Bob",
                    sessionId = "session-bob",
                    key = fakeDidKey(1),
                    roomId = roomId.value,
                    // Still minted until Task 1.8 retires it.
                    resumeSecret = ResumeSecret.generate(),
                    returnKind = returnKind,
                ),
            )
            fromHost(
                AdmitMessage.Welcome(
                    assignedPeerId = hostId.value,
                    displayName = "Host",
                    sessionId = hostId.value,
                    key = hostDevice.id,
                ),
            )
        }

        /** Advance [duration] in [TICK] steps, with the host pinging while the link is up. */
        suspend fun advance(duration: Duration) {
            var left = duration
            while (left > Duration.ZERO) {
                if (fake.state.value !is SeamState.Torn) fake.deliver(hostId, HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray())
                test.testScheduler.advanceTimeBy(TICK)
                test.testScheduler.runCurrent()
                left -= TICK
            }
        }

        /**
         * The link drops and re-forms. Asserts the joiner noticed — its reconnect episode started —
         * and returns the Hello it sent once the link was back, or null if it sent none.
         */
        suspend fun dropAndHeal(): AdmitMessage.Hello? {
            val mark = log.mark()
            val flights = (room as SeamRoom).resumeFlightsStarted()
            val before = hellosToHost().size
            link.enterWeaving()
            advance(TICK * 3)
            assertTrue(
                log.since(mark).any { it is MembershipEvent.Partitioned && it.peerId == hostId },
                "rig: the joiner saw its host link drop: ${log.since(mark).joinToString { it.describe() }}",
            )
            link.recover()
            test.testScheduler.runCurrent()
            assertTrue(room.resumeFlightsStarted() > flights, "rig: a reconnect flight started")
            return hellosToHost().drop(before).lastOrNull()
        }
    }

    private suspend fun TestScope.scripted(): Scripted {
        val fake = FakeSeam(selfId = bob, initialPeers = setOf(bob, hostId, eve))
        val link = FlakyLifecycleSeam(fake, backgroundScope)
        val joiner = SeamRoomFactory(
            loom = InMemoryLoom(),
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = fakeKeyStore(1),
            heartbeatConfig = joinerConfig,
            admitTimeout = 1.seconds,
        ).adopt(link, SessionRole.Joiner, memberName = "Bob", reweave = { link })
        val s = Scripted(this, fake, link, joiner)
        testScheduler.runCurrent()
        val first = s.broadcastHellos().single()
        s.challenge(first)
        s.welcome(returnKind = null)
        assertTrue(joiner.roster.value.any { it.id == hostId }, "rig: the joiner was admitted")
        s.advance(TICK * 3)
        return s
    }

    private fun List<MembershipEvent>.render(): String = joinToString { it.describe() }

    @Test
    fun reconnectLoopSendsHelloNotResume() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.welcome(returnKind = ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertEquals(0, s.resumes(), "no Resume frame, ever") },
            { assertEquals(1, s.broadcastHellos().size, "the re-Hello is addressed to the host, not broadcast (Amendment A § 1.7)") },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(Liveness.Connected, s.room.roster.value.single { it.id == hostId }.liveness, "the episode closed") },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, "a restore closes the arc with Resumed: ${since.render()}") },
            { assertEquals(1, s.hellosToHost().size, "one re-Hello was enough") },
            { assertEquals(0, s.sentTo(eve).count { it is AdmitMessage.Hello }, "nothing went to the bystander") },
        )
        s.room.leave()
    }

    /**
     * The host had already let the seat go, so the re-Hello was admitted as a new membership: a
     * `Welcome` without `Restored`. The room keeps that admission, closes the arc, and sends no second
     * Hello (Amendment C: a fresh admission after the window closed is a success).
     */
    @Test
    fun freshAdmissionAfterSeatLossIsNotFollowedByASecondHello() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.welcome(returnKind = null)
        val hellos = s.allHellos()
        s.advance(joinerConfig.reconnectWindow * 2)

        val since = s.log.since(mark)
        assertAll(
            { assertEquals(hellos, s.allHellos(), "no Hello after a completed fresh admission") },
            { assertEquals(0, s.resumes()) },
            { assertTrue(since.none { it is MembershipEvent.HostLost || it is MembershipEvent.AdmissionFailed }, since.render()) },
            { assertEquals(Liveness.Connected, s.room.roster.value.single { it.id == hostId }.liveness, "the room is still in session") },
            { assertTrue(since.any { it is MembershipEvent.Recovered && it.peerId == hostId }, "the arc closes on the host: ${since.render()}") },
            { assertTrue(since.none { it is MembershipEvent.Resumed }, "nothing was resumed: ${since.render()}") },
        )
        s.room.leave()
    }

    /** A terminal refusal from the host whose challenge this joiner answered ends the episode with its code. */
    @Test
    fun refusalIsSurfacedWithItsCode() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.fromHost(AdmitMessage.Reject("key-conflict", RejectCode.KeyConflict))
        val hellos = s.allHellos()
        s.advance(joinerConfig.reconnectWindow)

        val lost = s.log.since(mark).filterIsInstance<MembershipEvent.HostLost>()
        assertAll(
            { assertEquals(1, lost.size, s.log.since(mark).render()) },
            { assertEquals(RejectCode.KeyConflict, assertIs<FailureReason.Refused>(lost.singleOrNull()?.reason).code) },
            { assertEquals(hellos, s.allHellos(), "a terminal code is not retried") },
            { assertEquals(0, s.resumes()) },
        )
    }

    /**
     * Amendment C: a terminal `Reject` counts only from the peer whose challenge this joiner answered.
     * A bystander's forged `Reject(BadProof)` lands mid-exchange and is ignored; the host's Welcome
     * then restores the seat.
     */
    @Test
    fun aTerminalRejectFromABystanderIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.from(eve, AdmitMessage.Reject("bad-proof: forged", RejectCode.BadProof))
        s.welcome(returnKind = ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(Liveness.Connected, s.room.roster.value.single { it.id == hostId }.liveness) },
            { assertEquals(1, s.hellosToHost().size, "the forged refusal did not even cost a retry") },
        )
        s.room.leave()
    }

    /**
     * Amendment C, the impostor's shape: a terminal `Reject` from the host that arrives before this
     * joiner answered any challenge for its current Hello is about someone else's exchange — an
     * impostor's `KeyConflict` aimed at this id — and is ignored.
     */
    @Test
    fun aTerminalRejectBeforeTheChallengeIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.fromHost(AdmitMessage.Reject("key-conflict: bob is held by another device", RejectCode.KeyConflict))
        s.challenge(reHello)
        s.welcome(returnKind = ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(1, s.hellosToHost().size) },
        )
        s.room.leave()
    }

    /**
     * Amendment C: a retryable `Reject` from anyone settles the flight and schedules another Hello —
     * here a bystander's, sent before the host challenged anything. The retry is what the host then
     * answers.
     */
    @Test
    fun aRetryableRejectFromABystanderSaysHelloAgain() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.from(eve, AdmitMessage.Reject("host-failed: forged", RejectCode.HostFailed))
        s.advance(joinerConfig.interval * 2)
        val retry = assertNotNull(s.hellosToHost().drop(1).firstOrNull(), "the bystander's refusal scheduled a second Hello")
        s.challenge(retry)
        s.welcome(returnKind = ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(2, s.hellosToHost().size, "one retry, then nothing more") },
            { assertTrue(!retry.joinerNonce.contentEquals(reHello.joinerNonce), "the retry carries a fresh nonce") },
        )
        s.room.leave()
    }

    /**
     * Amendment C: the host's own `Welcome` closes the episode even between attempts. A bystander's
     * retryable `Reject` settled the flight, and while the loop waits out its interval the host's
     * `Welcome` for the challenged Hello arrives: the loop says no second Hello.
     */
    @Test
    fun aWelcomeBetweenAttemptsClosesTheEpisode() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.from(eve, AdmitMessage.Reject("host-failed: forged", RejectCode.HostFailed))
        val pendingAfterReject = (s.room as SeamRoom).hasPendingResume()
        s.welcome(returnKind = ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow * 2)

        val since = s.log.since(mark)
        assertAll(
            { assertFalse(pendingAfterReject, "precondition: the bystander's refusal settled the flight") },
            { assertEquals(1, s.hellosToHost().size, "the Welcome closed the episode: no second Hello") },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(Liveness.Connected, s.room.roster.value.single { it.id == hostId }.liveness, "the episode closed") },
        )
        s.room.leave()
    }

    /** Amendment C: a retryable refusal — here `HostFailed` from the host itself — only schedules another Hello. */
    @Test
    fun aRetryableRejectSaysHelloAgain() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.fromHost(AdmitMessage.Reject("host-failed: could not consult its window", RejectCode.HostFailed))
        s.advance(joinerConfig.interval * 2)
        val retry = assertNotNull(s.hellosToHost().drop(1).firstOrNull(), "the refusal scheduled a second Hello")
        s.challenge(retry)
        s.welcome(returnKind = ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(2, s.hellosToHost().size, "one retry, then nothing more") },
            { assertTrue(!retry.joinerNonce.contentEquals(reHello.joinerNonce), "every Hello carries a fresh nonce") },
        )
        s.room.leave()
    }

    /**
     * Inherited obligations 4 and 5: the host says this room was superseded while its reconnect is in
     * flight. The room ends with `Left(self, Superseded)`, the reconnect loop stops, no Hello follows,
     * and no `HostLost` is announced for a room that was told why it ended.
     */
    @Test
    fun aSupersededJoinerMidReconnectSaysNoMoreHello() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        val successor = PeerId("bob-2")
        val reconnectingBefore = (s.room as SeamRoom).isReconnectRunning()
        s.fromHost(AdmitMessage.Farewell(bob.value, supersededBy = successor.value))
        val hellos = s.allHellos()
        s.advance(joinerConfig.reconnectWindow * 2)

        val since = s.log.since(mark)
        assertAll(
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Left(bob, LeaveReason.Superseded(successor))), since.filterIsInstance<MembershipEvent.Left>()) },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(hellos, s.allHellos(), "a superseded room never says Hello again") },
            { assertTrue(reconnectingBefore, "rig: the reconnect loop was running when the Farewell came") },
            { assertTrue(!(s.room as SeamRoom).isReconnectRunning(), "the reconnect loop stopped") },
        )
    }

    // ── A real host ──────────────────────────────────────────────────────────

    /** A real host and joiner, on their own devices, the joiner's link wrapped so it can drop and heal. */
    private class RealPair(
        val hostTap: AdmitTap,
        val host: Room,
        val joiner: Room,
        val path: FaultySeam,
        val link: FlakyLifecycleSeam,
        val hold: ObservedHold,
    )

    private suspend fun TestScope.realPair(hostConfig: HeartbeatConfig): RealPair {
        val base = InMemoryLoom()
        val hostTap = AdmitTap(base.weave(Rendezvous.New(Pattern("rehello"))))
        val path = FaultySeam(base.weave(Rendezvous.Existing(InMemoryTag("rehello"))), backgroundScope)
        val link = FlakyLifecycleSeam(path, backgroundScope)
        val hold = ObservedHold()
        val host = SeamRoomFactory(
            base, backgroundScope, virtualClock(), fakeKeyStore(0),
            heartbeatConfig = hostConfig, reconnectControllerFactory = hold.factory,
        ).adopt(hostTap, SessionRole.Host, memberName = "Host", roomId = roomId)
        val joiner = SeamRoomFactory(base, backgroundScope, virtualClock(), fakeKeyStore(1), heartbeatConfig = joinerConfig)
            .adopt(link, SessionRole.Joiner, memberName = "Bob", reweave = { link })
        testScheduler.runCurrent()
        assertEquals(1, host.roster.value.size, "rig: the joiner was admitted")
        return RealPair(hostTap, host, joiner, path, link, hold)
    }

    private fun TestScope.ticks(n: Int) = repeat(n) {
        testScheduler.advanceTimeBy(TICK)
        testScheduler.runCurrent()
    }

    /**
     * The #1637 blip: the joiner's link drops and re-forms faster than the host's timeout, so the host
     * still holds the seat as connected. The re-Hello restores it with no window to consult; the old
     * `Resume` would have been refused `ResumeWindowNotYetOpen` until the dwell gave up.
     */
    @Test
    fun blipTheHostNeverSawRestoresByHelloAlone() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = realPair(hostConfig = joinerConfig)
        val hostLog = EventLog(p.host, backgroundScope)
        val joinerLog = EventLog(p.joiner, backgroundScope)
        ticks(3)
        val mark = hostLog.mark()
        val joinerMark = joinerLog.mark()

        p.path.partition()
        ticks(4)
        p.link.enterWeaving()
        ticks(2)
        p.link.recover()
        p.path.heal()
        ticks(20)

        val edges = hostLog.edgesSince(mark)
        assertAll(
            { assertEquals(2, p.hostTap.received<AdmitMessage.Hello>(), "rig: the joiner reconnected and said Hello again") },
            { assertEquals(0, p.hostTap.received<AdmitMessage.Resume>(), "no Resume on the wire") },
            { assertTrue(hostLog.since(mark).none { it is MembershipEvent.Partitioned }, "rig: the host never saw the blip") },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(p.joiner.selfId)), edges, edges.joinToString { it.describe() }) },
            { assertTrue(p.hold.calls.isEmpty(), "a connected seat is restored without the controller") },
            { assertTrue(joinerLog.since(joinerMark).none { it is MembershipEvent.HostLost }) },
            { assertEquals(Liveness.Connected, p.joiner.roster.value.single { it.id == p.host.selfId }.liveness) },
        )
        p.joiner.leave()
        p.host.leave()
    }

    /** The outage outlasts the host's timeout, so the seat is held partitioned and restored through `tryResume`. */
    @Test
    fun outageTheHostSawRestoresByHelloAlone() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = realPair(hostConfig = HeartbeatConfig(interval = TICK, timeout = 300.milliseconds, reconnectWindow = 4.seconds))
        val hostLog = EventLog(p.host, backgroundScope)
        val joinerLog = EventLog(p.joiner, backgroundScope)
        ticks(3)
        val mark = hostLog.mark()
        val joinerMark = joinerLog.mark()

        p.path.partition()
        ticks(6)
        assertTrue(
            hostLog.since(mark).any { it is MembershipEvent.Partitioned && it.peerId == p.joiner.selfId },
            "rig: the host held the seat as partitioned",
        )
        p.link.enterWeaving()
        ticks(2)
        p.link.recover()
        p.path.heal()
        ticks(20)

        val edges = hostLog.edgesSince(mark)
        assertAll(
            { assertEquals(0, p.hostTap.received<AdmitMessage.Resume>(), "no Resume on the wire") },
            { assertEquals(2, p.hostTap.received<AdmitMessage.Hello>(), "rig: the reconnect said Hello") },
            { assertEquals(listOf(p.joiner.selfId), p.hold.calls.map { it.peerId }, "the held seat went through tryResume") },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(p.joiner.selfId)), edges, edges.joinToString { it.describe() }) },
            { assertTrue(joinerLog.since(joinerMark).none { it is MembershipEvent.HostLost }) },
        )
        p.joiner.leave()
        p.host.leave()
    }

    private companion object {
        val TICK = 100.milliseconds
    }
}
