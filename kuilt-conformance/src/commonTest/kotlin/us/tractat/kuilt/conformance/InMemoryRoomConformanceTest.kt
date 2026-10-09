package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestScope

/**
 * Verifies the reference [us.tractat.kuilt.session.SeamRoomFactory] over
 * [us.tractat.kuilt.core.InMemoryLoom] satisfies the full [RoomConformanceSuite].
 *
 * Keeping this in `:kuilt-conformance` (rather than `:kuilt-session`) lets
 * `:kuilt-session` stay free of a dependency on `:kuilt-conformance`,
 * and exercises the suite from an external consumer — the same pattern
 * as [InMemoryLoomConformanceTest] for [us.tractat.kuilt.conformance.SeamConformanceSuite].
 *
 * The default [newHarness] implementation from [RoomConformanceSuite] is used:
 * a [us.tractat.kuilt.test.FaultyLoom]-wrapped [us.tractat.kuilt.core.InMemoryLoom]
 * with [fastHeartbeatConfig] and an injected clock. Resume mapping properties use an observed
 * real reconnect controller so each property proves which host verdict reached the mapper.
 */
class InMemoryRoomConformanceTest : RoomConformanceSuite() {
    override fun newResumeHarness(scope: TestScope): ResumeHarness =
        referenceResumeHarness(scope, fastHeartbeatConfig)
}
