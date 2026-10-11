@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the dispatch boundary without advancing time or relying on real-thread timing. */
class MeshConstructionDispatchCancellationTest {
    @Test
    fun cancellationBeforeDispatchClosesTheConnection() = cancelBeforeDispatch(1)

    @Test
    fun cancellationBeforeDispatchClosesEveryConnection() = cancelBeforeDispatch(3)

    private fun cancelBeforeDispatch(linkCount: Int) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connections = List(linkCount) { ProbeConnection() }
            // Enter construction, create its handshake children, and park at awaitAll. With DEFAULT
            // those children are queued but have never run; the test cancels before draining the queue.
            val build = async(start = CoroutineStart.UNDISPATCHED) {
                hubMesh(PeerId("self"), connections, dispatcher, Random(7))
            }
            assertAll(
                { assertFalse(build.isCompleted, "rig: construction reached a suspension") },
                {
                    assertEquals(
                        linkCount, build.children.single().children.count(),
                        "rig: every handshake child exists before cancellation",
                    )
                },
                { assertTrue(connections.all { it.collections == 0 }, "rig: dispatcher has not run") },
            )
            build.cancel()
            runCurrent()
            assertAll(
                { assertTrue(build.isCompleted && build.isCancelled, "cancellation finishes construction") },
                {
                    assertEquals(
                        List(linkCount) { 1 }, connections.map { it.closeCalls },
                        "every connection must close even when cancellation precedes handshake dispatch",
                    )
                },
                {
                    assertEquals(
                        connections.map { it.collections }, connections.map { it.collectionEnds },
                        "every pump that started must terminate",
                    )
                },
            )
        }

    @Test
    fun bufferedSendsLeaveAllLinksWaitingForRepliesAndPumpsLiveUntilClose() = checkLinkLifetimes(false)

    @Test
    fun suspendedSendsAllowEveryLinkToStartAndPumpsLiveUntilClose() = checkLinkLifetimes(true)

    private fun checkLinkLifetimes(suspendSend: Boolean) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connections = List(3) { ProbeConnection(suspendSend) }
            val build = async(start = CoroutineStart.UNDISPATCHED) {
                hubMesh(PeerId("self"), connections, dispatcher, Random(7))
            }
            assertAll(
                { assertEquals(List(3) { 1 }, connections.map { it.sends }, "send prefixes run inline") },
                {
                    assertEquals(
                        List(3) { if (suspendSend) 0 else 1 }, connections.map { it.sendCompletions },
                        "buffered sends finish inline; backpressured sends park before the next link starts",
                    )
                },
                { assertEquals(List(3) { 0 }, connections.map { it.collections }, "pumps remain dispatched") },
            )
            runCurrent()
            assertAll(
                { assertEquals(List(3) { 1 }, connections.map { it.sends }, "every handshake starts") },
                { assertEquals(List(3) { 1 }, connections.map { it.collections }, "every pump starts") },
                { assertFalse(build.isCompleted, "no peer has replied") },
            )
            // Let the last link finish first. A blocked earlier link must not serialize handshakes.
            connections.asReversed().forEachIndexed { index, conn ->
                conn.sendGate.complete(Unit)
                conn.reply.complete(MeshWire.encodeHello(PeerId("peer-$index"), meshNonce((index + 1).toByte())))
                runCurrent()
            }
            assertTrue(build.isCompleted, "all replies must finish construction without a timeout")
            val mesh = build.await()
            assertAll(
                { assertEquals(4, mesh.peers.value.size, "all links are admitted") },
                { assertEquals(List(3) { 0 }, connections.map { it.closeCalls }, "successful links stay open") },
                {
                    assertEquals(
                        List(3) { 0 }, connections.map { it.collectionEnds },
                        "pumps outlive the construction coroutine",
                    )
                },
            )
            mesh.close()
            runCurrent()
            assertAll(
                { assertEquals(List(3) { 1 }, connections.map { it.closeCalls }, "mesh owns each close") },
                { assertEquals(List(3) { 1 }, connections.map { it.collectionEnds }, "mesh stops each pump") },
            )
        }

    // Adversarial raw close deliberately violates incoming completion: only cancelling the wrapper's
    // pump can end collection. A suspending close also exposes a missing NonCancellable shield.
    private class ProbeConnection(suspendSend: Boolean = false) : Connection {
        val sendGate = CompletableDeferred<Unit>().also { if (!suspendSend) it.complete(Unit) }
        val reply = CompletableDeferred<ByteArray>()
        var sends = 0
        var sendCompletions = 0
        var collections = 0
        var collectionEnds = 0
        var closeCalls = 0

        override val incoming: Flow<ByteArray> = flow {
            collections++
            try {
                emit(reply.await())
                awaitCancellation()
            } finally {
                collectionEnds++
            }
        }

        override suspend fun send(frame: ByteArray) {
            sends++
            sendGate.await()
            sendCompletions++
        }

        override suspend fun close() {
            yield()
            closeCalls++
        }
    }
}
