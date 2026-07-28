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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #1819 — a peer-supplied `chunkCount` that contradicts the one already bound for a msgId must not
 * be able to silence the seam.
 *
 * [ChunkCodec.Reassembler.feed] used to size its slot array from whichever chunk arrived *first*
 * and then index it with a later chunk's `chunkIndex`, so 16 bytes from a peer threw out of
 * [NearbySeam]'s `assembleFrame`. That exception escaped the `payloadReceived` collector and killed
 * `receiveJob`; because `disconnectLoop` is a sibling under a `SupervisorJob`, `state` never latched
 * [SeamState.Torn] — the seam went permanently deaf with no close reason and no reconnect trigger.
 */
class NearbyMalformedChunkTest {

    private val self = PeerId("self")
    private val remote = PeerId("remote")
    private val endpointId = "ep-remote"

    /** [NearbyApi] whose only live surface is [payloadReceived], driven directly by the test. */
    private class PayloadDrivenNearbyApi : NearbyApi {
        val payloads = MutableSharedFlow<PayloadReceived>(extraBufferCapacity = 16)

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
        override val payloadReceived: Flow<PayloadReceived> = payloads.asSharedFlow()
        override val endpointDisconnected: Flow<EndpointDisconnected> = emptyFlow()
    }

    /** Hand-build a raw chunk with arbitrary (possibly hostile) header fields. */
    private fun rawChunk(msgId: Int, chunkIndex: Int, chunkCount: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(ChunkCodec.HEADER_SIZE + payload.size)
        out[0] = (msgId ushr 24).toByte()
        out[1] = (msgId ushr 16).toByte()
        out[2] = (msgId ushr 8).toByte()
        out[3] = msgId.toByte()
        out[4] = (chunkIndex ushr 8).toByte()
        out[5] = chunkIndex.toByte()
        out[6] = (chunkCount ushr 8).toByte()
        out[7] = chunkCount.toByte()
        payload.copyInto(out, ChunkCodec.HEADER_SIZE)
        return out
    }

    @Test
    fun aChunkCountMismatchDoesNotSilenceTheSeam() = runTest {
        val api = PayloadDrivenNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val seam = NearbySeam(
            selfId = self,
            endpointPeers = mutableMapOf(endpointId to remote),
            endpointPeersMutex = Mutex(),
            api = api,
            sharedPeers = MutableStateFlow(setOf(self, remote)),
            scope = scope,
            msgIdCounter = MsgIdCounter(),
        )
        assertIs<SeamState.Woven>(seam.state.value)

        // try/finally so a failing assertion cannot leak the seam scope into the next test.
        try {
            val received = mutableListOf<Swatch>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                seam.incoming.collect { received += it }
            }

            // The 16-byte attack from #1819: chunk 0-of-2 binds the assembly, then a chunk claiming
            // index 5 of 6 for the same msgId indexes past it.
            api.payloads.emit(PayloadReceived(endpointId, rawChunk(msgId = 7, 0, 2, byteArrayOf(0xA))))
            api.payloads.emit(PayloadReceived(endpointId, rawChunk(msgId = 7, 5, 6, byteArrayOf(0xB))))

            // The seam must still be alive and delivering.
            val good = "still listening".encodeToByteArray()
            for (chunk in ChunkCodec.encode(good, msgId = 8)) {
                api.payloads.emit(PayloadReceived(endpointId, chunk))
            }

            assertEquals(1, received.size, "the seam still delivers after the malformed chunk")
            assertTrue(received[0].toByteArray().contentEquals(good), "and delivers the right bytes")
            assertIs<SeamState.Woven>(seam.state.value, "no silent teardown")
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }
}
