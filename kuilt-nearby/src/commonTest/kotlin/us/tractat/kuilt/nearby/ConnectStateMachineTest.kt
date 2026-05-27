package us.tractat.kuilt.nearby

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectStateMachineTest {

    /**
     * A [NearbyApi] whose event flows are driven manually, recording the
     * side-effecting calls the state machine makes. Events are emitted from
     * [ConnectStateMachine.run]'s `trigger` lambda, which runs only after the
     * machine's collectors have subscribed (they launch UNDISPATCHED), so no
     * event is dropped.
     */
    private class ControllableNearbyApi : NearbyApi {
        val connInit = MutableSharedFlow<ConnectionInitiated>(extraBufferCapacity = 8)
        val connResult = MutableSharedFlow<ConnectionResult>(extraBufferCapacity = 8)
        val payloads = MutableSharedFlow<PayloadReceived>(extraBufferCapacity = 8)
        val found = MutableSharedFlow<EndpointFound>(extraBufferCapacity = 8)
        val disconnects = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 8)

        val accepted = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, ByteArray>>()

        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startAdvertising(displayName: String, serviceId: String) {}
        override suspend fun stopAdvertising() {}
        override suspend fun startDiscovery(serviceId: String) {}
        override suspend fun stopDiscovery() {}
        override suspend fun requestConnection(displayName: String, endpointId: String) {}
        override suspend fun acceptConnection(endpointId: String) { accepted += endpointId }
        override suspend fun disconnect(endpointId: String) {}
        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) { sent += endpointId to bytes }

        override val endpointFound: Flow<EndpointFound> = found
        override val connectionInitiated: Flow<ConnectionInitiated> = connInit
        override val connectionResult: Flow<ConnectionResult> = connResult
        override val payloadReceived: Flow<PayloadReceived> = payloads
        override val endpointDisconnected: Flow<EndpointDisconnected> = disconnects
    }

    @Test
    fun happyPathResolvesWithRemoteIdentity() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val machine = ConnectStateMachine(PeerId("me"), api, endpointId = "ep1", serviceId = "svc")

            val link =
                machine.run(backgroundScope) {
                    api.connInit.emit(ConnectionInitiated("ep1", "host"))
                    api.connResult.emit(ConnectionResult("ep1", success = true))
                    api.payloads.emit(PayloadReceived("ep1", PeerId("remote").value.encodeToByteArray()))
                }

            assertEquals("ep1", link.endpointId)
            assertEquals(PeerId("remote"), link.remotePeerId)
            assertTrue("ep1" in api.accepted, "machine accepted the connection")
            assertEquals(
                PeerId("me").value,
                api.sent.single().second.decodeToString(),
                "machine sent its own identity after CONNECTED",
            )
        }

    @Test
    fun rejectionThrowsConnectionFailed() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val machine = ConnectStateMachine(PeerId("me"), api, endpointId = "ep1", serviceId = "svc")

            val outcome =
                runCatching {
                    machine.run(backgroundScope) {
                        api.connInit.emit(ConnectionInitiated("ep1", "host"))
                        api.connResult.emit(ConnectionResult("ep1", success = false, reason = "rejected"))
                    }
                }

            assertTrue(
                outcome.exceptionOrNull() is ConnectionFailedException,
                "expected ConnectionFailedException, got ${outcome.exceptionOrNull()}",
            )
        }

    @Test
    fun timesOutWhenNoResultArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val machine =
                ConnectStateMachine(PeerId("me"), api, endpointId = "ep1", serviceId = "svc", timeoutMs = 1_000)

            // trigger emits nothing → withTimeout fires on the virtual clock.
            val outcome = runCatching { machine.run(backgroundScope) {} }

            assertTrue(
                outcome.exceptionOrNull() is TimeoutCancellationException,
                "expected TimeoutCancellationException, got ${outcome.exceptionOrNull()}",
            )
        }
}
