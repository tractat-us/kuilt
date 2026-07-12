package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Minimal in-test stub of [NwApi] — enough to drive the identity handshake. The
 * full `FakeNwApi`/`FakeNwRadio` is Task 2.6; here we only need the two event
 * flows the machine collects plus a record of `send` calls.
 *
 * Event flows have buffer capacity so the test can `tryEmit` synchronously once
 * the machine's UNDISPATCHED collectors have subscribed.
 */
private class StubNwApi : NwApi {
    val sent = mutableListOf<Pair<NwConnectionId, ByteArray>>()

    val bytes = MutableSharedFlow<NwBytesReceived>(extraBufferCapacity = 16)
    val closed = MutableSharedFlow<NwConnectionClosed>(extraBufferCapacity = 16)

    override val bytesReceived: Flow<NwBytesReceived> get() = bytes
    override val connectionClosed: Flow<NwConnectionClosed> get() = closed
    override val endpointFound: Flow<NwEndpoint> = MutableSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = MutableSharedFlow()

    override fun availability(): FabricAvailability = FabricAvailability.Available

    override suspend fun send(connectionId: NwConnectionId, bytes: ByteArray) {
        sent += connectionId to bytes
    }

    override suspend fun startListening(serviceName: String, serviceType: String) = Unit
    override suspend fun stopListening() = Unit
    override suspend fun startBrowsing(serviceType: String) = Unit
    override suspend fun stopBrowsing() = Unit
    override suspend fun connect(endpoint: NwEndpoint) = Unit
    override suspend fun disconnect(connectionId: NwConnectionId) = Unit
}

class NwConnectMachineTest {

    private val selfId = PeerId("self-peer")
    private val remoteId = PeerId("remote-peer")
    private val cid = NwConnectionId("c1")

    @Test
    fun identityExchangeResolvesLinkWithRemotePeerId() = runTest(StandardTestDispatcher()) {
        val api = StubNwApi()
        val machine = NwConnectMachine(selfId, api, NwFramer(), timeoutMs = 5_000L)

        val link = async { machine.run(backgroundScope, cid) }
        // Let the machine subscribe (UNDISPATCHED) and send its own identity.
        testScheduler.runCurrent()
        // Now the remote's identity frame arrives.
        api.bytes.tryEmit(NwBytesReceived(cid, encodeFrame(remoteId.value.encodeToByteArray())))
        val resolved = link.await()

        val sentFrame = api.sent.single()
        val decodedSelf = NwFramer().decode(sentFrame.second).single().decodeToString()
        assertAll(
            { assertEquals(remoteId, resolved.remotePeerId, "resolved remote identity") },
            { assertEquals(cid, resolved.connectionId, "resolved connection id") },
            { assertEquals(cid, sentFrame.first, "sent our identity on our connection") },
            { assertEquals(selfId.value, decodedSelf, "sent our own selfId as first frame") },
        )
    }

    @Test
    fun closeBeforeIdentityFailsWithHandshakeException() = runTest(StandardTestDispatcher()) {
        val api = StubNwApi()
        val machine = NwConnectMachine(selfId, api, NwFramer(), timeoutMs = 5_000L)

        // A detached supervisor scope (test dispatcher, parentless SupervisorJob)
        // isolates the expected failure to await() — async neither propagates it up
        // to cancel the test scope, nor leaves an uncompleted child for runTest.
        val supervised = CoroutineScope(coroutineContext + SupervisorJob())
        val link = supervised.async { machine.run(backgroundScope, cid) }
        testScheduler.runCurrent()
        api.closed.tryEmit(NwConnectionClosed(cid))

        assertFailsWith<NwHandshakeException> { link.await() }
        supervised.cancel()
    }

    @Test
    fun remoteIdentityEmittedConcurrentlyWithKickoffIsNotLost() = runTest(StandardTestDispatcher()) {
        // Regression guard for subscribe-before-trigger (UNDISPATCHED collectors):
        // the machine must have subscribed before it sends/kicks off, so an identity
        // frame delivered right after runCurrent() is not lost and the handshake resolves.
        val api = StubNwApi()
        val machine = NwConnectMachine(selfId, api, NwFramer(), timeoutMs = 5_000L)

        val link = async { machine.run(backgroundScope, cid) }
        // One runCurrent drains the machine's kickoff turn (subscribe + send + trigger),
        // then we emit immediately — a lost-subscription bug would hang here (timeout).
        testScheduler.runCurrent()
        api.bytes.tryEmit(NwBytesReceived(cid, encodeFrame(remoteId.value.encodeToByteArray())))

        assertEquals(remoteId, link.await().remotePeerId)
    }

    @Test
    fun freshPeerIdIsUniqueAcrossManyCalls() {
        val n = 1_000
        val ids = (1..n).map { freshPeerId() }.toSet()
        assertEquals(n, ids.size, "every freshPeerId() must be distinct (cross-device uniqueness, #1405)")
    }
}
