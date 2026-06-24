package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class RoomEventsReplayTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    /**
     * #692 regression: a consumer that subscribes to [Room.events] *after* the admit
     * handshake completes (the `KtorRoomHost.start { onRoom }` race) must still learn
     * that the peer joined. With a lossy `replay = 0` events flow the `Joined` was
     * dropped into the void and the consumer hung forever.
     */
    @Test
    fun `a late events subscriber still receives a join emitted before it subscribed`() = runTest {
        val loom = InMemoryLoom()
        val host = LoomRoomHost(loom, Pattern("server"), clock = zeroClock)
        val hostRoomReady = CompletableDeferred<Room>()
        val hostJob = backgroundScope.launch { host.start { room -> hostRoomReady.complete(room) } }
        val hostRoom = hostRoomReady.await()

        val joiner = SeamRoomFactory(loom, backgroundScope, zeroClock).join(InMemoryTag("server"))

        // Admit completes: the host emits Joined while NOTHING is collecting events yet.
        hostRoom.roster.first { it.size == 1 }

        // Only now does a consumer subscribe — it must still see the already-emitted Joined.
        val received = async(start = CoroutineStart.UNDISPATCHED) { hostRoom.events.first() }
        runCurrent()

        assertTrue(received.isCompleted, "late subscriber must receive the buffered Joined event")
        val event = received.await()
        assertTrue(event is MembershipEvent.Joined, "expected Joined, got $event")
        assertEquals(joiner.selfId, event.member.id)
        hostJob.cancel()
    }
}
