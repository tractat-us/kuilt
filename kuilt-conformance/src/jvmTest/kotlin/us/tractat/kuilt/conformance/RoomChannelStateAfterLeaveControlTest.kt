package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The positive control for [RoomConformanceSuite.channelStateTornAfterLeave] (#3129): a room whose
 * channel views never leave `Woven` must fail it.
 *
 * Without this the property has no red anywhere in the tree. The suite's only named subclass is the
 * reference, and the reference's views do go `Torn` after a leave, so a version of the property that
 * asserted nothing would be green there too. The rooms here are the reference's own rooms with one
 * thing changed: each channel view reports a state that is never `Torn`. So a red here can only come
 * from the line that reads the state.
 *
 * `jvmTest` for [RoomConformanceGapDeclarationTest]'s reason: the suite's methods return
 * `TestResult`, which is `Unit` here but a `Promise` on wasm, where [assertFailsWith] would pass on a
 * body that had not run yet.
 */
class RoomChannelStateAfterLeaveControlTest {

    private fun stuckOpen(): RoomConformanceSuite =
        object : RoomConformanceSuite() {
            override fun newResumeHarness(scope: TestScope): ResumeHarness =
                referenceResumeHarness(scope, fastHeartbeatConfig)

            override fun newHarness(scope: CoroutineScope): RoomHarness {
                var clockMs = 0L
                val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
                val factory = StuckOpenRoomFactory(
                    SeamRoomFactory(loom = InMemoryLoom(), scope = scope, clock = clock, heartbeatConfig = fastHeartbeatConfig),
                )
                return RoomHarness(
                    hostFactory = factory,
                    joinerFactory = factory,
                    faults = FaultInjection.Unsupported("https://github.com/tractat-us/kuilt/issues/3129"),
                    clock = clock,
                    advanceClock = { ms -> clockMs += ms },
                )
            }
        }

    @Test
    fun aRoomWhoseViewsNeverTearFailsTheObligation() {
        val failure = assertFailsWith<AssertionError> { stuckOpen().channelStateTornAfterLeave() }
        // The shape of the red, not just its presence: the wait names the view it gave up on, which
        // is the joiner's held view because the joiner leaves first. A red from the rig instead (the
        // precondition, or an admission that never happened) would name something else.
        assertTrue(
            "the channel view never read Torn: the joiner's view taken before it left" in failure.message.orEmpty(),
            "expected the Torn wait to be what failed, got: ${failure.message}",
        )
    }

    /** The reference's rooms, with every channel view's state replaced by one that stays `Woven`. */
    private class StuckOpenRoomFactory(private val delegate: RoomFactory) : RoomFactory {
        override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room =
            StuckOpenRoom(delegate.host(pattern, memberName, roomId))

        override suspend fun join(tag: Tag, memberName: String?): Room =
            StuckOpenRoom(delegate.join(tag, memberName))
    }

    private class StuckOpenRoom(private val delegate: Room) : Room by delegate {
        override fun channel(id: String): Seam = StuckOpenView(delegate.channel(id))
    }

    private class StuckOpenView(private val delegate: Seam) : Seam by delegate {
        private val stuck = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = stuck.asStateFlow()
    }
}
