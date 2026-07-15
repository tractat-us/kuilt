@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A no-op [Connection] identified only by [id]; the test never sends or receives on it. */
private class FakeConnection(val id: String) : Connection {
    override suspend fun send(frame: ByteArray) = Unit
    override val incoming: Flow<ByteArray> = emptyFlow()
    override suspend fun close() = Unit
}

class AcceptPumpTest {

    /** A conn whose handling hangs must not block a later conn's handling (concurrency), and must be
     *  abandoned after the handshake timeout (no permanent wedge). */
    @Test
    fun aHungHandshakeDoesNotStarveLaterConnections() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val handled = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()   // never completed → conn "hangs"
        val conns = ArrayDeque(listOf("hang", "good-1", "good-2"))
        val source = object : ConnectionSource {
            override suspend fun accept(): Connection =
                FakeConnection(conns.removeFirstOrNull() ?: CompletableDeferred<String>().await())
        }
        val failures = mutableListOf<Throwable>()
        val job = acceptPump(source, handshakeTimeout = 2.seconds, onFailure = { failures += it }) { conn ->
            val id = (conn as FakeConnection).id
            if (id == "hang") gate.await() else handled += id
        }
        advanceTimeBy(3.seconds); runCurrent()      // past the 2s handshake timeout → hung conn abandoned
        assertEquals(setOf("good-1", "good-2"), handled.toSet())
        assertTrue(failures.any { it is HandshakeTimeoutException }, "the hung conn surfaced a handshake timeout")
        job.cancel()
    }
}
