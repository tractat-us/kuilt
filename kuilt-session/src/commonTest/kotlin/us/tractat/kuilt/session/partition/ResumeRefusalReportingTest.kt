@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session.partition

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoom
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.virtualClock
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The joiner's refusal loop must **say so while it is happening**.
 *
 * A joiner whose reconnect is refused retryably says Hello again every `HeartbeatConfig.interval`
 * for the whole `reconnectWindow`. Before #1637's post-mortem the only trace of that was a terminal
 * `HostLost` a minute later — by which point the mechanism that produced it is gone, and a real
 * hardware capture was misdiagnosed twice because the refusal loop left no evidence of itself.
 *
 * [JoinerResumeMachine] records each refusal as a [ResumeRefusal] and logs it at INFO. The record is
 * what these tests assert: the *identities and state* a diagnosis needs — which host, which
 * [RejectCode], how many attempts in, how far through the reconnect budget. Asserting the record
 * rather than the rendered text keeps the tests about the data.
 *
 * Since #3159 Task 1.7 a reconnect says Hello, and a host that never saw the blip restores the seat
 * at once, so the #1637 blip no longer produces a refusal loop. A host this class scripts does:
 * it answers every re-Hello's proof with a retryable `Reject(HostFailed)`, as a host whose window
 * controller keeps throwing would.
 */
class ResumeRefusalReportingTest {
    private val hostId = PeerId("host")
    private val joinerId = PeerId("joiner")
    private val roomId = RoomId("room-1")

    private val config = HeartbeatConfig(
        interval = TICK,
        timeout = 1000.milliseconds,
        reconnectWindow = 4.seconds,
    )

    /** A joiner over `FlakyLifecycleSeam(FakeSeam)`, admitted by a host this class plays. */
    private inner class Rig(private val test: TestScope, val fake: FakeSeam, val link: FlakyLifecycleSeam, val room: SeamRoom) {
        private var answered = 0

        private fun hellosToHost(): List<AdmitMessage.Hello> =
            fake.directed.filter { it.first == hostId }.mapNotNull { AdmitMessage.decode(it.second) as? AdmitMessage.Hello }

        private suspend fun fromHost(message: AdmitMessage) {
            if (fake.state.value is SeamState.Torn) return
            fake.deliver(hostId, AdmitMessage.encode(message))
            test.testScheduler.runCurrent()
        }

        suspend fun challenge(hello: AdmitMessage.Hello) {
            val signed = Challenge.sign(
                fakeDeviceKey(0), roomId.value, hostId.value, joinerId.value,
                assertNotNull(hello.joinerNonce), ByteArray(Challenge.NONCE_SIZE) { 3 },
            )
            fromHost(AdmitMessage.Challenge(signed.wire))
        }

        suspend fun admit(hello: AdmitMessage.Hello) {
            challenge(hello)
            fromHost(AdmitMessage.Welcome(joinerId.value, "Joiner", "s", fakeDidKey(1), roomId = roomId.value, answers = hello.joinerNonce, membership = "membership-1"))
            fromHost(AdmitMessage.Welcome(hostId.value, "Host", hostId.value, fakeDeviceKey(0).id))
        }

        /** Every re-Hello not yet answered is challenged, then refused retryably. */
        suspend fun refuseEveryReHello() {
            val hellos = hellosToHost()
            while (answered < hellos.size) {
                val hello = hellos[answered++]
                challenge(hello)
                fromHost(AdmitMessage.Reject("host-failed: the window controller threw", RejectCode.HostFailed, hello.joinerNonce))
            }
        }

        /** One [TICK], with the host pinging and every new re-Hello refused. */
        suspend fun tick(refuse: Boolean = true) {
            if (fake.state.value !is SeamState.Torn) fake.deliver(hostId, HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray())
            test.testScheduler.advanceTimeBy(TICK)
            test.testScheduler.runCurrent()
            if (refuse) refuseEveryReHello()
        }

        fun dropAndHeal() {
            link.enterWeaving()
            test.testScheduler.advanceTimeBy(TICK * 3)
            test.testScheduler.runCurrent()
            link.recover()
            test.testScheduler.runCurrent()
        }
    }

    private suspend fun TestScope.rig(): Rig {
        val fake = FakeSeam(selfId = joinerId, initialPeers = setOf(joinerId, hostId))
        val link = FlakyLifecycleSeam(fake, backgroundScope)
        val room = SeamRoomFactory(
            loom = InMemoryLoom(),
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = fakeKeyStore(1),
            heartbeatConfig = config,
            admitTimeout = 1.seconds,
        ).adopt(link, SessionRole.Joiner, memberName = "Joiner", reweave = { link }) as SeamRoom
        testScheduler.runCurrent()
        val rig = Rig(this, fake, link, room)
        val first = fake.broadcasts.mapNotNull { AdmitMessage.decode(it) as? AdmitMessage.Hello }.single()
        rig.admit(first)
        assertTrue(room.roster.value.any { it.id == hostId }, "rig: the joiner was admitted")
        repeat(3) { rig.tick(refuse = false) }
        return rig
    }

    @Test
    fun `a refused reconnect records the host the code and how far the episode has got`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            rig.dropAndHeal()
            rig.refuseEveryReHello()
            val first = assertNotNull(rig.room.lastResumeRefusal(), "a refused reconnect must leave a record while the loop is still running")
            repeat(5) { rig.tick() }
            val later = assertNotNull(rig.room.lastResumeRefusal(), "the record must survive the retries")

            assertAll(
                { assertEquals(hostId, first.host, "the record must name the host that refused us") },
                { assertEquals(roomId.value, first.roomId, "the record must name the room being returned to") },
                { assertEquals(RejectCode.HostFailed, first.code, "the record carries the refusal's own code") },
                { assertEquals(1, first.attempt, "the first refusal of an episode is attempt 1") },
                { assertTrue(later.attempt > first.attempt, "each refusal is reported, not just the first (first=${first.attempt} later=${later.attempt})") },
                { assertEquals(config.reconnectWindow, first.budget, "the record carries the budget the elapsed time is measured against") },
                {
                    assertTrue(
                        first.elapsed > Duration.ZERO && first.elapsed < config.reconnectWindow,
                        "elapsed places the refusal inside the reconnect budget (was ${first.elapsed})",
                    )
                },
            )
            rig.room.leave()
        }

    /**
     * The volume bound. The loop says Hello once per [HeartbeatConfig.interval], so an episode can emit
     * at most one record per interval of its budget. A regression that logged per frame, or per
     * re-weave attempt, blows this.
     */
    @Test
    fun `the refusal record advances once per retry and not once per frame`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            rig.dropAndHeal()
            repeat((config.reconnectWindow / TICK).toInt() + 10) { rig.tick() }

            val ceiling = (config.reconnectWindow / config.interval).toInt()
            val record = assertNotNull(rig.room.lastResumeRefusal(), "the episode must have been refused")
            assertTrue(
                record.attempt in 2..ceiling,
                "an episode reports at most one refusal per retry interval (attempts=${record.attempt}, ceiling=$ceiling)",
            )
        }

    /**
     * A window lost to retryable refusals ends `HostLost(Refused)`, carrying the last refusal's code,
     * not `WindowExpired`: the consumer can tell a host that kept refusing from one that never answered.
     */
    @Test
    fun `a window lost to retryable refusals ends HostLost Refused with their code`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val events = mutableListOf<MembershipEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.room.events.collect { events += it } }
            rig.dropAndHeal()
            repeat((config.reconnectWindow / TICK).toInt() + 10) { rig.tick() }

            val lost = events.filterIsInstance<MembershipEvent.HostLost>()
            assertAll(
                { assertTrue(assertNotNull(rig.room.lastResumeRefusal(), "precondition: the episode was refused").attempt >= 2) },
                { assertEquals(1, lost.size, "the window was lost (events: $events)") },
                {
                    val reason = lost.singleOrNull()?.reason
                    assertIs<FailureReason.Refused>(reason, "a window lost to refusals says so (reason: $reason)")
                    assertEquals(RejectCode.HostFailed, reason.code)
                },
            )
        }

    /** A healthy session is never refused, so it must add no refusal record — and no log line. */
    @Test
    fun `a session that is never refused records nothing`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            repeat(20) { rig.tick() }
            assertNull(rig.room.lastResumeRefusal(), "a session that never dropped must not report a refusal")
            rig.room.leave()
        }

    private companion object {
        val TICK = 100.milliseconds
    }
}
