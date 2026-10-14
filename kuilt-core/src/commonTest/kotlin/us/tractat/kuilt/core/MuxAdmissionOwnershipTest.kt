@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.fabric.CloseCountingConnection
import us.tractat.kuilt.core.fabric.MeshWire
import us.tractat.kuilt.core.fabric.meshNonce
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MuxAdmissionOwnershipTest {
    @Test
    fun cancellationAfterRegistrationStillDeregistersTheRecord() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val delegate = StandardTestDispatcher(testScheduler)
        var loom: MuxServerLoom? = null
        var cancellationFired = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val current = loom
                if (!cancellationFired && current != null && current.connectedPeers.value.isNotEmpty()) {
                    cancellationFired = true
                    current.close()
                }
                delegate.dispatch(context, block)
            }
        }
        val (raw, remote) = connectionPair()
        val conn = CloseCountingConnection(raw)
        val server = MuxServerLoom(
            InMemoryConnectionSource().also { it.offer(conn) },
            CoroutineScope(backgroundScope.coroutineContext + dispatcher), PeerId("server"),
            RoomAuthorizer.AllowAll, delegate, Random(7),
        )
        loom = server
        remote.send(MeshWire.encodeHello(PeerId("client"), meshNonce(1)))
        runCurrent()
        try {
            assertAll(
                { assertTrue(cancellationFired, "rig: a registered record existed before pump dispatch") },
                { assertTrue(server.backgroundJobsForTest.all { it.isCompleted }, "loom pumps stop") },
                { assertTrue(server.connectedPeers.value.isEmpty(), "registered read-loop teardown must run even before dispatch") },
                { assertEquals(0, conn.closeCalls, "registered seams retain the provider-owned lifecycle") },
                { assertFalse(conn.collectionEnded.isCompleted, "the adopted wrapper remains provider-owned") },
            )
        } finally {
            server.close()
            remote.close()
            runCurrent()
        }
    }

    @Test
    fun cancellingOnlyTheAcceptPumpKeepsRegisteredReadersWorking() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (raw, remote) = connectionPair()
        val conn = CloseCountingConnection(raw)
        val server = MuxServerLoom(
            InMemoryConnectionSource().also { it.offer(conn) }, backgroundScope, PeerId("server"),
            RoomAuthorizer.AllowAll, StandardTestDispatcher(testScheduler), Random(7),
        )
        val room = server.host(Pattern("room"))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { room.incoming.collect { received += it } }
        remote.send(MeshWire.encodeHello(PeerId("client"), meshNonce(1)))
        runCurrent()
        assertEquals(setOf(PeerId("client")), server.connectedPeers.value, "rig: registration finished")
        server.backgroundJobsForTest.first().cancel()
        remote.send(MeshWire.encodeData(NamedFrame.encode("room".encodeToByteArray(), byteArrayOf(7))))
        runCurrent()
        try {
            assertAll(
                { assertEquals(listOf(7), received.map { it.toByteArray().single().toInt() }, "registered readers outlive acceptance") },
                { assertEquals(0, conn.closeCalls, "accept cancellation must not close an adopted connection") },
                { assertTrue(PeerId("client") in room.peers.value, "the room sees the adopted peer") },
            )
            remote.close()
            runCurrent()
            assertAll(
                { assertTrue(server.connectedPeers.value.isEmpty(), "peer loss deregisters the record") },
                { assertFalse(PeerId("client") in room.peers.value, "peer loss deregisters room membership") },
                { assertTrue(conn.collectionEnded.isCompleted, "peer loss stops the wrapper") },
            )
        } finally {
            server.close()
            remote.close()
            room.close()
        }
    }

    @Test
    fun cancellationBeforeRegistrationClosesTheTemporarySeam() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val delegate = StandardTestDispatcher(testScheduler)
        val pumpParents = mutableSetOf<Job>()
        var cancellationFired = false
        lateinit var loom: MuxServerLoom
        // The supplied dispatcher runs the wrapper pump, then the constructed mesh's reader.
        // Cancel admission on that second scope's first dispatch: the mesh exists, but Mux has
        // not received it or registered it yet. No real threads, sleeps, or production hooks.
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val parent = context[Job]?.parent
                if (parent != null && pumpParents.add(parent) && pumpParents.size == 2) {
                    cancellationFired = true
                    loom.close()
                }
                delegate.dispatch(context, block)
            }
        }
        val (raw, remote) = connectionPair()
        val conn = CloseCountingConnection(raw)
        loom = MuxServerLoom(
            InMemoryConnectionSource().also { it.offer(conn) }, backgroundScope, PeerId("server"),
            RoomAuthorizer.AllowAll, dispatcher, Random(7),
        )
        remote.send(MeshWire.encodeHello(PeerId("client"), meshNonce(1)))
        runCurrent()
        try {
            assertAll(
                { assertTrue(cancellationFired, "rig: construction dispatched the mesh reader and cancelled admission") },
                { assertTrue(loom.connectedPeers.value.isEmpty(), "the temporary seam was never registered") },
                { assertEquals(1, conn.closeCalls, "cancelled registration must close the temporary seam") },
                { assertTrue(conn.collectionEnded.isCompleted, "closing the temporary seam stops its wrapper pump") },
                { assertTrue(pumpParents.all { !it.isActive }, "no temporary mesh scope may survive") },
            )
        } finally {
            loom.close()
            remote.close()
        }
    }
}
