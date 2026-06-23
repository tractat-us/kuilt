package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class LoomRoomHostTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `drives the admit handshake over an in-memory loom under virtual time`() = runTest {
        val loom = InMemoryLoom()
        val host = LoomRoomHost(loom, Pattern("server"), clock = zeroClock)
        val hostRoomReady = CompletableDeferred<Room>()

        val hostJob = backgroundScope.launch {
            host.start { room -> hostRoomReady.complete(room) }
        }
        val hostRoom = hostRoomReady.await()

        // A joiner connects over the same in-memory mesh — no socket bound — and
        // completes Hello → Welcome → onPeer purely under runTest virtual time.
        val joinerFactory = SeamRoomFactory(loom = loom, scope = backgroundScope, clock = zeroClock)
        val joinerRoom = joinerFactory.join(InMemoryTag("server"))

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }

        assertTrue(
            hostRoom.roster.value.any { it.id == joinerRoom.selfId },
            "host roster must contain the admitted joiner",
        )
        hostJob.cancel()
    }

    @Test
    fun `start a second time on the same host throws`() = runTest {
        val host = LoomRoomHost(InMemoryLoom(), Pattern("dup"), clock = zeroClock)

        val firstStarted = CompletableDeferred<Unit>()
        val job = backgroundScope.launch {
            host.start {
                firstStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        firstStarted.await()

        val ex = runCatching { host.start { } }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "second start must throw IllegalStateException, got $ex")
        job.cancel()
    }
}
