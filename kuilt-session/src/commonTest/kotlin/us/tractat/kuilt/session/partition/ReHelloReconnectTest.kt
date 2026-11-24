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
import us.tractat.kuilt.session.AdmissionFailure
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
 * A joiner's reconnect says Hello again (#3159, Task 1.7), and acts only on an answer that names that
 * Hello (#3215).
 *
 * The reconnect loop, its back-off and its teardown are unchanged; only what it sends is. After a
 * re-weave the joiner sends a fresh Hello **to its host** (never a broadcast, which a foreign host on
 * a flat loom would answer), answers the Challenge, and the episode ends on the host's answer to that
 * Hello — a `Welcome` or a `Reject` whose `answers` is its nonce:
 * - a `Welcome` naming the membership the joiner already held: the seat came back;
 * - a `Welcome` naming another membership: the seat was gone and this was a fresh admission, which
 *   the room keeps (Amendment C: a success, not `HostLost`). Its `returnKind` decides neither;
 * - a terminal `Reject`: the refusal is surfaced with its code; a retryable one schedules another
 *   Hello.
 *
 * An answer naming any other Hello — an impostor's refusal sent to this id, a bystander's forgery, a
 * `Welcome` left over from an earlier Hello — is ignored.
 *
 * Two harnesses. Most tests script the host by hand over a [FakeSeam] behind a [FlakyLifecycleSeam],
 * so each chooses the host's answer and counts every frame the joiner sends. Three run a real host,
 * where the claim is about the whole exchange.
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

        /**
         * The self Welcome and the host intro: the shape of an admission answering [hello], naming
         * [membership] and marked [returnKind].
         */
        suspend fun welcome(hello: AdmitMessage.Hello, membership: String, returnKind: ReturnKind? = null) {
            fromHost(
                AdmitMessage.Welcome(
                    assignedPeerId = bob.value,
                    displayName = "Bob",
                    sessionId = "session-bob",
                    key = fakeDidKey(1),
                    roomId = roomId.value,
                    returnKind = returnKind,
                    answers = hello.joinerNonce,
                    membership = membership,
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

    /** A joiner room that has said its first Hello and been answered nothing yet. */
    private suspend fun TestScope.unadmitted(): Scripted {
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
        assertEquals(1, s.broadcastHellos().size, "rig: the joiner said its first Hello")
        return s
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
        s.welcome(first, FIRST_MEMBERSHIP)
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
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
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
     * `Welcome` naming another membership id. The room keeps that admission, closes the arc, and sends
     * no second Hello (Amendment C: a fresh admission after the window closed is a success).
     */
    @Test
    fun freshAdmissionAfterSeatLossIsNotFollowedByASecondHello() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.welcome(reHello, SECOND_MEMBERSHIP)
        val hellos = s.allHellos()
        s.advance(joinerConfig.reconnectWindow * 2)

        val since = s.log.since(mark)
        assertAll(
            { assertEquals(hellos, s.allHellos(), "no Hello after a completed fresh admission") },
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
        s.fromHost(AdmitMessage.Reject("key-conflict", RejectCode.KeyConflict, reHello.joinerNonce))
        val hellos = s.allHellos()
        s.advance(joinerConfig.reconnectWindow)

        val lost = s.log.since(mark).filterIsInstance<MembershipEvent.HostLost>()
        assertAll(
            { assertEquals(1, lost.size, s.log.since(mark).render()) },
            { assertEquals(RejectCode.KeyConflict, assertIs<FailureReason.Refused>(lost.singleOrNull()?.reason).code) },
            { assertEquals(hellos, s.allHellos(), "a terminal code is not retried") },
        )
    }

    /**
     * A refusal at the host's Hello gate names the Hello too (#3215; #3616 item 3), so a reconnect the
     * gate refuses ends `Refused` with the gate's code at once, rather than waiting out its window.
     */
    @Test
    fun aHelloGateRefusalEndsTheReconnectWithItsCode() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.fromHost(AdmitMessage.Reject("room-mismatch: elsewhere", RejectCode.RoomMismatch, reHello.joinerNonce))
        val hellos = s.allHellos()
        s.advance(joinerConfig.interval * 3)

        val lost = s.log.since(mark).filterIsInstance<MembershipEvent.HostLost>()
        assertAll(
            { assertEquals(1, lost.size, "ended within three intervals, not at the window: ${s.log.since(mark).render()}") },
            { assertEquals(RejectCode.RoomMismatch, assertIs<FailureReason.Refused>(lost.singleOrNull()?.reason).code) },
            { assertEquals(hellos, s.allHellos(), "a terminal code is not retried") },
        )
    }

    /**
     * A bystander's forged `Reject(BadProof)` lands mid-exchange. It cannot name the reconnect's Hello,
     * which went to the host alone, so it is ignored; the host's Welcome then restores the seat (#3215).
     */
    @Test
    fun aTerminalRejectFromABystanderIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.from(eve, AdmitMessage.Reject("bad-proof: forged", RejectCode.BadProof, OTHER_NONCE))
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
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
     * The impostor's shape, which the verified-challenger rule could not defend (#3616 item 1): a
     * terminal `Reject` **from the host**, sent to this id about an impostor's exchange, arriving after
     * this joiner answered the host's challenge. It names the impostor's Hello, not this joiner's, so
     * it is ignored (#3215), and the host's Welcome then restores the seat.
     */
    @Test
    fun aTerminalRejectNamingAnotherHelloIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.fromHost(AdmitMessage.Reject("key-conflict: bob is held by another device", RejectCode.KeyConflict, OTHER_NONCE))
        val pendingAfterReject = (s.room as SeamRoom).hasPendingResume()
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(pendingAfterReject, "the refusal of another Hello left this one's flight waiting") },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(1, s.hellosToHost().size) },
        )
        s.room.leave()
    }

    /**
     * A `Welcome` from the host naming an earlier Hello — here the admission's, the membership the
     * joiner holds and all — arrives while the reconnect's own Hello waits for its answer. It answers
     * nothing this joiner is waiting on, so it closes nothing; the Welcome naming the reconnect's Hello
     * then does (#3215).
     */
    @Test
    fun aWelcomeNamingAnEarlierHelloClosesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val admissionHello = s.broadcastHellos().single()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.welcome(admissionHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        val pendingAfterStale = (s.room as SeamRoom).hasPendingResume()
        val resumedByStale = s.log.since(mark).count { it is MembershipEvent.Resumed }
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(pendingAfterStale, "the stale Welcome settled nothing") },
            { assertEquals(0, resumedByStale, "the stale Welcome closed nothing: ${since.render()}") },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
        )
        s.room.leave()
    }

    /**
     * The joiner reads a restore from the membership, not from `returnKind` (#3215): a Welcome marked
     * `Restored` that names another membership is a fresh admission, and one that names the joiner's
     * own membership is its seat coming back even unmarked.
     */
    @Test
    fun aRestoreIsReadFromTheMembershipNotTheMark() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val firstMark = s.log.mark()
        val firstReHello = assertNotNull(s.dropAndHeal(), "the first reconnect says Hello again")
        s.challenge(firstReHello)
        s.welcome(firstReHello, SECOND_MEMBERSHIP, ReturnKind.Restored)
        s.advance(TICK * 3)
        val first = s.log.since(firstMark)

        val secondMark = s.log.mark()
        val secondReHello = assertNotNull(s.dropAndHeal(), "the second reconnect says Hello again")
        s.challenge(secondReHello)
        s.welcome(secondReHello, SECOND_MEMBERSHIP, returnKind = null)
        s.advance(TICK * 3)
        val second = s.log.since(secondMark)

        assertAll(
            { assertTrue(first.none { it is MembershipEvent.Resumed }, "marked Restored, another membership: fresh: ${first.render()}") },
            { assertTrue(first.any { it is MembershipEvent.Recovered && it.peerId == hostId }, "the first arc closed: ${first.render()}") },
            { assertEquals(1, second.count { it is MembershipEvent.Resumed && it.peerId == bob }, "unmarked, the membership it now holds: restored: ${second.render()}") },
            { assertTrue((first + second).none { it is MembershipEvent.HostLost }) },
        )
        s.room.leave()
    }

    /**
     * A retryable `Reject` that names another Hello — a bystander's, sent before the host challenged
     * anything — settles nothing and schedules no retry (#3215; under Amendment C a retryable refusal
     * from anyone cost a Hello). The host then answers the one Hello.
     */
    @Test
    fun aRetryableRejectFromABystanderIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.from(eve, AdmitMessage.Reject("host-failed: forged", RejectCode.HostFailed, OTHER_NONCE))
        val pendingAfterReject = (s.room as SeamRoom).hasPendingResume()
        s.challenge(reHello)
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(pendingAfterReject, "the forged refusal settled nothing") },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(1, s.hellosToHost().size, "the forged refusal did not cost a retry") },
        )
        s.room.leave()
    }

    /**
     * Amendment C: the host's own `Welcome` closes the episode even between attempts. A retryable
     * `Reject` naming the Hello settled the flight, and while the loop waits out its interval the
     * host's `Welcome` for that same Hello arrives: the loop says no second Hello. (An honest host sends
     * one answer per Hello; the two here stand for an answer that settles the attempt and one that
     * closes the episode, whatever settled the attempt first.)
     */
    @Test
    fun aWelcomeBetweenAttemptsClosesTheEpisode() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.fromHost(AdmitMessage.Reject("host-failed: passing", RejectCode.HostFailed, reHello.joinerNonce))
        val pendingAfterReject = (s.room as SeamRoom).hasPendingResume()
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow * 2)

        val since = s.log.since(mark)
        assertAll(
            { assertFalse(pendingAfterReject, "precondition: the refusal settled the flight") },
            { assertEquals(1, s.hellosToHost().size, "the Welcome closed the episode: no second Hello") },
            { assertTrue(since.none { it is MembershipEvent.HostLost }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
            { assertEquals(Liveness.Connected, s.room.roster.value.single { it.id == hostId }.liveness, "the episode closed") },
        )
        s.room.leave()
    }

    /** A retryable refusal of this Hello — here `HostFailed` from the host — only schedules another Hello. */
    @Test
    fun aRetryableRejectSaysHelloAgain() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.fromHost(AdmitMessage.Reject("host-failed: could not consult its window", RejectCode.HostFailed, reHello.joinerNonce))
        s.advance(joinerConfig.interval * 2)
        val retry = assertNotNull(s.hellosToHost().drop(1).firstOrNull(), "the refusal scheduled a second Hello")
        s.challenge(retry)
        s.welcome(retry, FIRST_MEMBERSHIP, ReturnKind.Restored)
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

    // ── A Reject that names no Hello (#3215, Amendment E's one exception) ────

    /**
     * Before its first admission a joiner acts on a `ProtocolMismatch` that names no Hello: a host
     * older than v7 cannot name one, and this is its answer to a v7 Hello. The join fails with that
     * code at once, rather than at its admit deadline.
     */
    @Test
    fun anUnnamedProtocolMismatchBeforeAdmissionFailsTheJoin() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = unadmitted()
        val mark = s.log.mark()
        s.fromHost(AdmitMessage.Reject("protocol-mismatch: 7 not in 6..6", RejectCode.ProtocolMismatch, answers = null))
        val failed = s.log.since(mark).filterIsInstance<MembershipEvent.AdmissionFailed>()

        assertAll(
            { assertEquals(1, failed.size, "the join failed at once: ${s.log.since(mark).render()}") },
            { assertEquals(RejectCode.ProtocolMismatch, assertIs<AdmissionFailure.Rejected>(failed.singleOrNull()?.reason).code) },
        )
    }

    /**
     * The exception is that one code. Any other refusal naming no Hello — here `BadProof` — is ignored
     * before admission as at any other time, and the join completes when the host admits it.
     */
    @Test
    fun anUnnamedOtherRefusalBeforeAdmissionIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = unadmitted()
        val mark = s.log.mark()
        val first = s.broadcastHellos().single()
        s.fromHost(AdmitMessage.Reject("bad-proof: forged", RejectCode.BadProof, answers = null))
        val failedByIt = s.log.since(mark).any { it is MembershipEvent.AdmissionFailed }
        s.challenge(first)
        s.welcome(first, FIRST_MEMBERSHIP)
        s.advance(TICK * 15)

        val since = s.log.since(mark)
        assertAll(
            { assertFalse(failedByIt, "the unnamed refusal failed nothing: ${since.render()}") },
            { assertTrue(since.none { it is MembershipEvent.AdmissionFailed }, "the join completed: ${since.render()}") },
            { assertTrue(s.room.roster.value.any { it.id == hostId }, "the joiner was admitted") },
        )
        s.room.leave()
    }

    /**
     * Once admitted, a `ProtocolMismatch` naming no Hello ends nothing, a reconnect included: only an
     * answer naming the reconnect's Hello can end it, and the host's Welcome then closes it.
     */
    @Test
    fun anUnnamedProtocolMismatchDuringAReconnectEndsNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val s = scripted()
        val mark = s.log.mark()
        val reHello = assertNotNull(s.dropAndHeal(), "the reconnect says Hello again")
        s.challenge(reHello)
        s.fromHost(AdmitMessage.Reject("protocol-mismatch: 7 not in 6..6", RejectCode.ProtocolMismatch, answers = null))
        val pendingAfterReject = (s.room as SeamRoom).hasPendingResume()
        s.welcome(reHello, FIRST_MEMBERSHIP, ReturnKind.Restored)
        s.advance(joinerConfig.reconnectWindow)

        val since = s.log.since(mark)
        assertAll(
            { assertTrue(pendingAfterReject, "the unnamed refusal settled nothing") },
            { assertTrue(since.none { it is MembershipEvent.HostLost || it is MembershipEvent.AdmissionFailed }, since.render()) },
            { assertEquals(1, since.count { it is MembershipEvent.Resumed && it.peerId == bob }, since.render()) },
        )
        s.room.leave()
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
     *
     * The host never saw the member go, so it announces nothing (#3215: `Resumed` is once per
     * partition), while the joiner, whose Welcome names the membership it held, closes its arc with
     * its own `Resumed`.
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
        val joinerSince = joinerLog.since(joinerMark)
        assertAll(
            { assertEquals(2, p.hostTap.received<AdmitMessage.Hello>(), "rig: the joiner reconnected and said Hello again") },
            { assertTrue(hostLog.since(mark).none { it is MembershipEvent.Partitioned }, "rig: the host never saw the blip") },
            { assertEquals(emptyList(), edges, "a connected seat's restore is silent: ${edges.joinToString { it.describe() }}") },
            { assertTrue(p.hold.calls.isEmpty(), "a connected seat is restored without the controller") },
            { assertEquals(1, joinerSince.count { it == MembershipEvent.Resumed(p.joiner.selfId) }, joinerSince.joinToString { it.describe() }) },
            { assertTrue(joinerSince.none { it is MembershipEvent.HostLost }) },
            { assertEquals(Liveness.Connected, p.joiner.roster.value.single { it.id == p.host.selfId }.liveness) },
        )
        p.joiner.leave()
        p.host.leave()
    }

    /**
     * The outage outlasts the host's timeout, so the seat is held partitioned and restored through
     * `tryResume`, with one `Resumed`. A second Hello after it finds the seat connected: answered, and
     * announced by nobody, since the partition has already ended (#3215: once per partition).
     */
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

        val again = (p.joiner as SeamRoom).sayHelloAgain()
        ticks(2)

        val edges = hostLog.edgesSince(mark)
        assertAll(
            { assertEquals(3, p.hostTap.received<AdmitMessage.Hello>(), "rig: the reconnect said Hello, then the second Hello") },
            { assertEquals(listOf(p.joiner.selfId), p.hold.calls.map { it.peerId }, "the held seat went through tryResume") },
            { assertEquals(ResumeResult.Success, again, "the second Hello found its own seat") },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(p.joiner.selfId)), edges, edges.joinToString { it.describe() }) },
            { assertTrue(joinerLog.since(joinerMark).none { it is MembershipEvent.HostLost }) },
        )
        p.joiner.leave()
        p.host.leave()
    }

    private companion object {
        val TICK = 100.milliseconds

        /** The membership the scripted host admits Bob under. */
        const val FIRST_MEMBERSHIP = "membership-1"

        /** Another membership: what a fresh admission is named. */
        const val SECOND_MEMBERSHIP = "membership-2"

        /** A nonce no Hello of Bob's carries: what an answer to someone else's Hello names. */
        val OTHER_NONCE = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 7 }
    }
}
