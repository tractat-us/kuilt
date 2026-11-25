@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A never-admitted joiner ends once (review of #3697), in the order a single thread can reach: a
 * refusal and then a superseded `Reject`, both naming the joiner's Hello, arrive together. The
 * refusal queues `failAdmission`, and the superseded `Reject` then claims the superseded ending and
 * queues its announcement behind it. So `failAdmission` runs after the claim and before the `Left`,
 * and must add nothing: the room ends `Left(self, Superseded(by))` and never `AdmissionFailed`.
 *
 * The other order, the superseded `Reject` landing after `failAdmission` has claimed its ending and
 * before its `leave`, has no suspension point in it and is pinned on real threads by the JVM's
 * `SupersededAfterAdmissionFailureTest`.
 */
class SupersededEndsOnceTest {
    private val self = PeerId("tab-a")
    private val host = PeerId("host")
    private val successor = PeerId("tab-b")

    @Test
    fun aRefusalQueuedAheadOfTheSupersededEndingAddsNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, host))
        val room = SeamRoomFactory(
            InMemoryLoom(),
            backgroundScope,
            virtualClock(),
            fakeKeyStore(1),
            heartbeatConfig = KEY_SEAT_HEARTBEAT,
            admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
        ).adopt(seam, AdoptRole.Joiner, memberName = "A") as SeamRoom
        val log = EventLog(room, backgroundScope)
        testScheduler.runCurrent()
        val hello = seam.broadcasts.mapNotNull { AdmitMessage.decode(it) as? AdmitMessage.Hello }.single()
        val nonce = checkNotNull(hello.joinerNonce) { "a current Hello carries a nonce" }
        val checks = mutableListOf<Boolean>()
        room.rejectCheckedForTest = { answersLatest -> checks += answersLatest }

        seam.deliver(host, AdmitMessage.encode(AdmitMessage.Reject("key-conflict: held", RejectCode.KeyConflict, nonce)))
        seam.deliver(host, AdmitMessage.encode(AdmitMessage.Reject.superseded(successor.value, nonce)))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT * 2)
        testScheduler.runCurrent()
        val terminal = log.since(0).filter { it is MembershipEvent.AdmissionFailed || (it is MembershipEvent.Left && it.peerId == self) }

        assertAll(
            { assertEquals(listOf(true, true), checks, "rig: both Rejects named the latest Hello, the refusal first") },
            {
                assertEquals(
                    listOf<MembershipEvent>(MembershipEvent.Left(self, LeaveReason.Superseded(successor))),
                    terminal,
                    terminal.joinToString { it.describe() },
                )
            },
        )
    }
}
