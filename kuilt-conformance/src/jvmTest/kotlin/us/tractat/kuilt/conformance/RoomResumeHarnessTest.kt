package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestScope
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
