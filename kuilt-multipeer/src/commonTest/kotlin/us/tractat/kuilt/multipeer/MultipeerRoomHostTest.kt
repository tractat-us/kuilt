package us.tractat.kuilt.multipeer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.Room
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultipeerRoomHostTest {

    @Test
    fun `start hands a Room to onRoom and suspends until cancelled`() = runTest {
        val loom = InMemoryLoom()
        val host = MultipeerRoomHost(loom, Pattern("test-room"))
        val received = CompletableDeferred<Room>()

        coroutineScope {
            val job = launch {
                host.start { room -> received.complete(room) }
            }
            val room = received.await()
            assertNotNull(room)
            assertTrue(room.selfId.value.isNotBlank(), "Room selfId must be non-blank")
            job.cancel()
        }
    }

    @Test
    fun `start a second time on the same host throws`() = runTest {
        val host = MultipeerRoomHost(InMemoryLoom(), Pattern("dup"))

        coroutineScope {
            val firstStarted = CompletableDeferred<Unit>()
            val job = launch {
                host.start {
                    firstStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            firstStarted.await()
            yield()

            val ex = runCatching { host.start { } }.exceptionOrNull()
            assertNotNull(ex, "second start must throw")
            assertTrue(ex is IllegalStateException, "expected IllegalStateException, got ${ex::class}")
            job.cancel()
        }
    }
}
