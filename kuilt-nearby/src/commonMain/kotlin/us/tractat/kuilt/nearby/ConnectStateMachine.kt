package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId

/**
 * Drives the Nearby Connections request→initiate→accept→result handshake
 * for one side of a connection. Suspends until the live link is established
 * or throws on failure / timeout.
 *
 * ## Identity exchange
 * Nearby assigns local-namespace endpoint IDs that are not stable across sessions.
 * To satisfy [Swatch.sender] stamping, each side sends its stable [PeerId] as the
 * very first BYTES payload immediately after the CONNECTED result. The machine
 * awaits both CONNECTED and the remote identity payload before resolving.
 *
 * ## Endpoint filtering
 * Because a single [NearbyApi] may be used for both roles simultaneously
 * (one loom, two state machines in the fake), events are filtered by endpoint ID:
 * - If [endpointId] is provided, only events with that ID are processed (discoverer path).
 * - If [endpointId] is null, the FIRST [ConnectionInitiated] event seen sets the target
 *   endpoint, after which all other events are filtered to that endpoint (advertiser path).
 *
 * @param selfId      This peer's stable identity.
 * @param api         The [NearbyApi] instance.
 * @param endpointId  Endpoint to filter on, or null for "first-seen" mode.
 * @param serviceId   Nearby service ID (informational).
 * @param timeoutMs   Timeout in milliseconds (default 30 s).
 */
internal class ConnectStateMachine(
    private val selfId: PeerId,
    private val api: NearbyApi,
    private val endpointId: String?,
    private val serviceId: String,
    private val timeoutMs: Long = 30_000L,
) {

    /**
     * Suspend until the handshake completes.
     *
     * The caller is responsible for having started advertising/discovery and
     * (for the discoverer) called [NearbyApi.requestConnection] before or
     * concurrently with this call.
     */
    suspend fun await(scope: CoroutineScope): ConnectedLink =
        withTimeout(timeoutMs) {
            val deferred = CompletableDeferred<ConnectedLink>()
            val job = launchListeners(scope, deferred)
            try {
                deferred.await()
            } finally {
                job.cancel()
            }
        }

    private fun launchListeners(
        scope: CoroutineScope,
        deferred: CompletableDeferred<ConnectedLink>,
    ): Job = scope.launch {
        val handshake = HandshakeState(initialEndpoint = endpointId)

        // Accept connection initiation when it arrives for our endpoint.
        launch {
            api.connectionInitiated.collect { event ->
                if (deferred.isCompleted) return@collect
                if (!handshake.claimEndpoint(event.endpointId)) return@collect
                api.acceptConnection(event.endpointId)
            }
        }

        // Observe connection result.
        launch {
            api.connectionResult.collect { event ->
                if (deferred.isCompleted) return@collect
                if (!handshake.isOurEndpoint(event.endpointId)) return@collect
                if (event.success) {
                    handshake.connected = true
                    api.sendBytesPayload(handshake.endpoint!!, selfId.value.encodeToByteArray())
                    handshake.maybeResolve(deferred)
                } else {
                    deferred.completeExceptionally(
                        ConnectionFailedException(event.endpointId, event.reason),
                    )
                }
            }
        }

        // Receive remote's identity payload (first BYTES for our endpoint).
        launch {
            api.payloadReceived.collect { event ->
                if (deferred.isCompleted) return@collect
                if (!handshake.isOurEndpoint(event.endpointId)) return@collect
                if (handshake.remoteSelfId == null) {
                    handshake.remoteSelfId = PeerId(event.bytes.decodeToString())
                    handshake.maybeResolve(deferred)
                }
            }
        }
    }

    private class HandshakeState(initialEndpoint: String?) {
        var endpoint: String? = initialEndpoint
            private set
        var connected: Boolean = false
        var remoteSelfId: PeerId? = null

        /**
         * Try to claim [candidate] as our target endpoint.
         * - If we already have a target, only returns true if [candidate] matches.
         * - If we don't have a target yet (first-seen mode), claims it and returns true.
         */
        fun claimEndpoint(candidate: String): Boolean {
            val current = endpoint
            if (current == null) {
                endpoint = candidate
                return true
            }
            return current == candidate
        }

        fun isOurEndpoint(candidate: String): Boolean = endpoint == candidate

        fun maybeResolve(deferred: CompletableDeferred<ConnectedLink>) {
            if (connected && remoteSelfId != null) {
                deferred.complete(ConnectedLink(endpoint!!, remoteSelfId!!))
            }
        }
    }
}

/** A resolved, live Nearby connection. */
internal data class ConnectedLink(
    val endpointId: String,
    val remotePeerId: PeerId,
)

/** Thrown when Nearby reports a connection failure. */
public class ConnectionFailedException(
    endpointId: String,
    reason: String?,
) : Exception("Connection to $endpointId failed: $reason")
