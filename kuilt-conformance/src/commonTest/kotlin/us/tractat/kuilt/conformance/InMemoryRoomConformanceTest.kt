package us.tractat.kuilt.conformance

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
 * a [us.tractat.kuilt.core.FaultyLoom]-wrapped [us.tractat.kuilt.core.InMemoryLoom]
 * with [fastHeartbeatConfig] and an injected clock. All 10 acceptance contracts
 * are covered, including partition / HostLost / resume tests.
 */
class InMemoryRoomConformanceTest : RoomConformanceSuite()
