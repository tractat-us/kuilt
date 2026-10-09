package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** JVM-only: direct calls to runTest properties return Unit here, but a promise on Wasm. */
class RoomResumeHarnessTest {
    private fun suite(
        change: (RoomConformanceSuite.ResumeHarness) -> RoomConformanceSuite.ResumeHarness,
    ): RoomConformanceSuite = object : RoomConformanceSuite() {
        override fun newResumeHarness(scope: TestScope): ResumeHarness =
            change(referenceResumeHarness(scope, fastHeartbeatConfig))
    }

    @Test
    fun anUnpreparedWindowFailsItsPrecondition() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(prepare = { token, _ -> token }) }.resumeExpiredHasExactCode()
        }
        assertContains(failure.message.orEmpty(), "resume rig must reach the requested host verdict")
    }

    @Test
    fun anUnobservedVerdictFailsEvenWhenTheReplyIsCorrect() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(hostVerdicts = { emptyList() }) }.resumeNotYetOpenHasExactCode()
        }
        assertContains(failure.message.orEmpty(), "the host must render exactly one verdict")
    }

    @Test
    fun aDeadSendCounterCannotProveLocalWindowClosed() {
        val failure = assertFailsWith<AssertionError> {
            suite { it.copy(resumeRequests = { 0 }) }.resumeAfterLeaveIsLocalWindowClosed()
        }
        assertContains(failure.message.orEmpty(), "the send observation must be live")
    }
}
