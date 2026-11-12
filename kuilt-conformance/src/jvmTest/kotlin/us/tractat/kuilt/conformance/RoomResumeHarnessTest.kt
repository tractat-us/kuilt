package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
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

    /**
     * Positive control for the forged-resume property (#3165): a host that checks only a token's
     * public fields lets the forgery through, and the property must red on it — on the follow-up
     * resume, which is the half that tells a spent window from an unspent one.
     */
    @Test
    fun aHostThatIgnoresTheSecretFailsTheForgedResumeProperty() {
        val failure = assertFailsWith<AssertionError> {
            object : RoomConformanceSuite() {
                override fun newResumeHarness(scope: TestScope): ResumeHarness =
                    referenceResumeHarness(scope, fastHeartbeatConfig, ignoreSecret = true)
            }.aResumeWithoutTheMembersSecretIsRefusedAndLeavesItsWindowOpen()
        }
        assertAll(
            { assertContains(failure.message.orEmpty(), "a forgery without the secret must be refused") },
            { assertContains(failure.message.orEmpty(), "the forgery must not spend its window") },
        )
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
