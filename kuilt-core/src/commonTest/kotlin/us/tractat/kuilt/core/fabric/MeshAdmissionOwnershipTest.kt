@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class MeshAdmissionOwnershipTest {
    @Test
    fun cancellingAdmissionClosesTheUnownedWrapper() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val entered = CompletableDeferred<Unit>()
        val mesh = hubMesh(
            PeerId("hub"), emptyList(), StandardTestDispatcher(testScheduler), Random(7),
            admission = LinkAdmission { _, _ -> entered.complete(Unit); awaitCancellation() },
        )
        val (raw, remote) = connectionPair()
        val conn = CloseCountingConnection(raw)
        val pump = backgroundScope.acceptPump(
            InMemoryConnectionSource().also { it.offer(conn) }, 1.hours,
        ) { mesh.addLink(it) }
        remote.send(MeshWire.encodeHello(PeerId("peer"), meshNonce(1)))
        runCurrent()
        assertAll(
            { assertTrue(entered.isCompleted, "rig: handshake finished and admission suspended") },
            { assertEquals(setOf(PeerId("hub")), mesh.peers.value) },
            { assertEquals(0, conn.closeCalls) },
        )
        pump.cancelAndJoin()
        runCurrent()
        try {
            assertAll(
                { assertEquals(1, conn.closeCalls, "cancelled admission must close the unowned connection") },
                { assertTrue(conn.collectionEnded.isCompleted, "the wrapper pump must stop too") },
            )
        } finally {
            mesh.close()
            remote.close()
        }
    }

    @Test
    fun cancellingTheReplaceArmKeepsTheWinnerReading() = cancelDuringDrain(replace = true)

    @Test
    fun cancellingTheKeepArmKeepsTheLoserDraining() = cancelDuringDrain(replace = false)

    private fun cancelDuringDrain(replace: Boolean) = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drained = mutableListOf<MeshDisplacement.Drained>()
        // One buffered frame and one backpressured frame hold stageMutex without a test-only hook.
        val mesh = hubMesh(
            PeerId("hub"), emptyList(), dispatcher, Random(0), policy = DeliveryPolicy(capacity = 1),
            drainBound = 1.hours,
            onDisplacement = { if (it is MeshDisplacement.Drained) drained += it },
        )
        val (rawA, remoteA) = connectionPair()
        val (rawB, remoteB) = connectionPair()
        val a = CloseCountingConnection(rawA)
        val b = CloseCountingConnection(rawB)
        val outboundA = mutableListOf<ByteArray>()
        val outboundB = mutableListOf<ByteArray>()
        backgroundScope.launch { remoteA.incoming.collect { outboundA += it } }
        backgroundScope.launch { remoteB.incoming.collect { outboundB += it } }
        backgroundScope.launch { mesh.addLink(a) }
        remoteA.send(MeshWire.encodeHello(PeerId("peer"), meshNonce(if (replace) 0xFF.toByte() else 0)))
        runCurrent()
        remoteA.send(MeshWire.encodeData(byteArrayOf(1)))
        remoteA.send(MeshWire.encodeData(byteArrayOf(2)))
        runCurrent()

        val pump = backgroundScope.acceptPump(
            InMemoryConnectionSource().also { it.offer(b) }, 1.hours,
        ) { mesh.addLink(it) }
        remoteB.send(MeshWire.encodeHello(PeerId("peer"), meshNonce(if (replace) 0 else 0xFF.toByte())))
        runCurrent()
        mesh.sendTo(PeerId("peer"), byteArrayOf(9))
        runCurrent()
        assertAll(
            {
                assertTrue((if (replace) outboundB else outboundA).any { it.contentEquals(MeshWire.encodeData(byteArrayOf(9))) },
                    "rig: dedup already adopted the expected winner")
            },
            {
                assertFalse((outboundA + outboundB).any { MeshWire.decode(it) == MeshWireFrame.Goodbye },
                    "rig: drain startup is waiting behind the backpressured reader")
            },
            { assertEquals(0, a.closeCalls + b.closeCalls) },
        )
        pump.cancelAndJoin()
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { mesh.incoming.collect { received += it } }
        runCurrent()
        val loser = if (replace) remoteA else remoteB
        val winner = if (replace) remoteB else remoteA
        loser.send(MeshWire.encodeData(byteArrayOf(3)))
        loser.send(MeshWire.encodeGoodbye())
        runCurrent()
        winner.send(MeshWire.encodeData(byteArrayOf(4)))
        runCurrent()
        try {
            assertAll(
                { assertEquals(listOf(1, 2, 3, 4), received.map { it.toByteArray().single().toInt() },
                    "adopted winner and drain readers must survive accept-pump cancellation") },
                { assertEquals(1, drained.size, "the adopted drain must finish on goodbye") },
                { assertEquals(if (replace) MeshDisplacement.Arm.Replace else MeshDisplacement.Arm.Keep, drained.singleOrNull()?.arm) },
                { assertEquals(0, (if (replace) b else a).closeCalls, "cancellation must not close the winner") },
                { assertEquals(1, (if (replace) a else b).closeCalls, "goodbye must dispose of the loser") },
            )
        } finally {
            mesh.close()
            remoteA.close()
            remoteB.close()
        }
        runCurrent()
        assertAll(
            { assertTrue(a.collectionEnded.isCompleted && b.collectionEnded.isCompleted, "teardown stops both wrappers") },
            { assertTrue(a.closeCalls > 0 && b.closeCalls > 0, "teardown closes both connections") },
        )
    }
}
