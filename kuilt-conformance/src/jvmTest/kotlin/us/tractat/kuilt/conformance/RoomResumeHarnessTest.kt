package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Positive controls for the return properties (#3159, Task 1.7): each rigs one observation of the
 * reference harness and shows the property it backs reds on it, so none of them can pass by absence.
 *
 * JVM-only: direct calls to runTest properties return Unit here, but a promise on Wasm.
 */
class RoomResumeHarnessTest {
    private fun suite(
        change: (RoomConformanceSuite.ResumeHarness) -> RoomConformanceSuite.ResumeHarness,
    ): RoomConformanceSuite = object : RoomConformanceSuite() {
        override fun newResumeHarness(scope: TestScope): ResumeHarness =
            change(referenceResumeHarness(scope, fastHeartbeatConfig))
    }

    /** A rig that releases nothing fails the fresh-admission property's precondition, never passes it. */
    @Test
    fun aReleaseThatReleasesNothingFailsItsPrecondition() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(releaseSeat = {}) }.aReturnAfterTheSeatWentIsAFreshAdmission()
        }
        assertContains(failure.message.orEmpty(), "the return rig released the joiner's seat on the host")
    }

    /**
     * A room-behaviour control, not an instrument one: a joiner whose room ends itself on the
     * `WindowClosed` verdict — granted the fresh admission, then let it go — fails the fresh-admission
     * property. Its roster still lists the host it had, so only the room's live state can catch it.
     */
    @Test
    fun aJoinerThatEndsItsRoomOnWindowClosedFailsTheFreshAdmissionProperty() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(joinerFactory = EndsOnWindowClosed(it.joinerFactory)) }.aReturnAfterTheSeatWentIsAFreshAdmission()
        }
        assertContains(failure.message.orEmpty(), "the joiner's room keeps the admission")
    }

    /** A dead Hello counter cannot prove a return said Hello. */
    @Test
    fun aDeadHelloCounterCannotProveAReturn() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(helloRequests = { 0 }) }.aReturnRestoresAHeldSeat()
        }
        assertContains(failure.message.orEmpty(), "the joiner must say Hello again, once")
    }

    /** The `Resume` counter is read: a room seen sending one fails the return property. */
    @Test
    fun aResumeFrameOnTheWireFailsTheReturnProperty() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(resumeRequests = { 1 }) }.aReturnRestoresAHeldSeat()
        }
        assertContains(failure.message.orEmpty(), "a return sends no Resume frame")
    }

    @Test
    fun aDeadSendCounterCannotProveLocalWindowClosed() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(helloRequests = { 0 }) }.resumeAfterLeaveIsLocalWindowClosed()
        }
        assertContains(failure.message.orEmpty(), "the send observation must be live")
    }
}

/** [delegate]'s rooms, except that a joiner leaves its room when a return is answered `WindowClosed`. */
private class EndsOnWindowClosed(private val delegate: RoomFactory) : RoomFactory {
    override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room = delegate.host(pattern, memberName, roomId)

    override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room = Ending(delegate.join(tag, memberName, deviceId))

    private class Ending(private val room: Room) : Room by room {
        override suspend fun resume(token: ResumeToken): ResumeResult.JoinerOutcome =
            room.resume(token).also { if (it == ResumeResult.WindowClosed) room.leave() }
    }
}
