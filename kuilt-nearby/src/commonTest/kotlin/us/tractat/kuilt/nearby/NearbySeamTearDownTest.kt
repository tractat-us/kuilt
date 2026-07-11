@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies [NearbySeam] honours the [us.tractat.kuilt.core.Seam] contract that `incoming`
 * completes once the seam reaches [SeamState.Torn] — including a *self-driven* teardown when
 * the last connected endpoint disconnects on its own (not via a local `close()`).
 */
class NearbySeamTearDownTest {

    private val self = PeerId("self")

    /**
     * Minimal [NearbyApi] whose only live surface is [endpointDisconnected], which the test
     * drives directly. Everything else is a no-op — the seam is constructed already-connected.
     */
    private class ControllableNearbyApi : NearbyApi {
        val disconnects = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 4)

        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startAdvertising(displayName: String, serviceId: String) {}
        override suspend fun stopAdvertising() {}
        override suspend fun startDiscovery(serviceId: String) {}
        override suspend fun stopDiscovery() {}
        override suspend fun requestConnection(displayName: String, endpointId: String) {}
        override suspend fun acceptConnection(endpointId: String) {}
        override suspend fun disconnect(endpointId: String) {}
        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {}

        override val endpointFound: Flow<EndpointFound> = emptyFlow()
        override val connectionInitiated: Flow<ConnectionInitiated> = emptyFlow()
        override val connectionResult: Flow<ConnectionResult> = emptyFlow()
        override val payloadReceived: Flow<PayloadReceived> = emptyFlow()
        override val endpointDisconnected: Flow<EndpointDisconnected> = disconnects.asSharedFlow()
    }

    /**
     * Build a seam that is already [SeamState.Woven]: [endpoints] are pre-populated and
     * [sharedPeers] already carries a remote peer, so [NearbySeam]'s `wovenWatcher` latches
     * Woven synchronously at construction.
     */
    private fun wovenSeam(
        api: NearbyApi,
        scope: CoroutineScope,
        endpoints: Map<String, PeerId>,
    ): NearbySeam {
        val sharedPeers = MutableStateFlow(setOf(self) + endpoints.values)
        return NearbySeam(
            selfId = self,
            endpointPeers = endpoints.toMutableMap(),
            endpointPeersMutex = Mutex(),
            api = api,
            sharedPeers = sharedPeers,
            scope = scope,
            msgIdCounter = MsgIdCounter(),
        )
    }

    @Test
    fun lastEndpointDisconnectLatchesTornAndCompletesIncoming() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val remote = PeerId("remote")
        val seam = wovenSeam(api, scope, mapOf("ep-remote" to remote))
        assertIs<SeamState.Woven>(seam.state.value)

        api.disconnects.emit(EndpointDisconnected("ep-remote"))

        // incoming completes (the spool closed) → toList returns immediately.
        val drained = seam.incoming.toList()
        assertTrue(drained.isEmpty(), "no frames were delivered")
        assertIs<SeamState.Torn>(seam.state.value)
    }

    @Test
    fun partialDisconnectDoesNotLatchTorn() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val a = PeerId("a")
        val b = PeerId("b")
        val seam = wovenSeam(api, scope, mapOf("ep-a" to a, "ep-b" to b))
        assertIs<SeamState.Woven>(seam.state.value)

        api.disconnects.emit(EndpointDisconnected("ep-a"))

        // One of two peers dropped: the session is still live.
        assertIs<SeamState.Woven>(seam.state.value)
        assertContains(seam.peers.value, b)
        // Still open: a send does not throw the closed-seam error.
        seam.broadcast(byteArrayOf(1))

        scope.coroutineContext[Job]?.cancel()
    }
}
