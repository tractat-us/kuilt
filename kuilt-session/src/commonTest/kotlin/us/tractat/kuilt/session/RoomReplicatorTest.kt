@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [RoomReplicator] produces identical convergence behaviour to the
 * hand-wired `room.channel(id)` + [SeamReplicator] pattern it replaces.
 *
 * The acceptance criterion from issue #243: two peers, one using [RoomReplicator]
 * and one hand-wiring [SeamReplicator] directly, must reach the same converged CRDT
 * value — proving the wrapper introduces no behavioural difference.
 */
class RoomReplicatorTest {

    private val config = SeamReplicatorConfig(expectVirtualTime = true)

    @Test
    fun `RoomReplicator converges identically to hand-wired SeamReplicator`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val testClock: () -> kotlin.time.Instant = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
            val hostRoom = SeamRoomFactory(loom, backgroundScope, testClock).host(Pattern("Alice"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope, testClock).join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            // Host uses the RoomReplicator wrapper.
            val repWrapper = RoomReplicator(
                room = hostRoom,
                id = "crdt-test",
                initial = GCounter.ZERO,
                stateSerializer = GCounter.serializer(),
                scope = backgroundScope,
                config = config,
            )

            // Joiner uses the hand-wired path that RoomReplicator replaces.
            val repHandWired = SeamReplicator(
                replica = ReplicaId(joinerRoom.selfId.value),
                seam = joinerRoom.channel("crdt-test"),
                initial = GCounter.ZERO,
                messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
                scope = backgroundScope,
                config = config,
            )

            repWrapper.apply(repWrapper.state.value.inc(repWrapper.replica, 3L))
            repHandWired.apply(repHandWired.state.value.inc(repHandWired.replica, 5L))

            testScheduler.advanceUntilIdle()

            assertEquals(
                repHandWired.state.value,
                repWrapper.state.value,
                "wrapper and hand-wired replicator must converge to the same state",
            )
            assertEquals(8L, repWrapper.state.value.value, "converged total must be 8")

            joinerRoom.leave()
            hostRoom.leave()
        }
}
