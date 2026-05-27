package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId

/**
 * Drives the Nearby Connections request→initiate→accept→result handshake
 * for one side of a connection. Suspends until the live link is established
 * or throws on failure / timeout.
 *
 * ## Subscribe-before-trigger
 * All event collectors are launched with [CoroutineStart.UNDISPATCHED] so they
 * have subscribed to the (hot, no-replay) event flows **before** [run] invokes
 * [trigger]. Without this, the kickoff (`requestConnection`) would emit
 * `ConnectionInitiated` before the collectors subscribed and the events would be
 * lost — the classic `MutableSharedFlow` emit-before-subscribe race that hangs
 * the handshake under `runTest`'s `StandardTestDispatcher`.
 *
 * ## Identity exchange
 * Nearby assigns local-namespace endpoint IDs that are not stable across peers,
 * so [us.tractat.kuilt.core.Swatch.sender] cannot be derived from an endpointId.
 * Each side sends its stable [PeerId] as the first BYTES payload immediately after
 * the CONNECTED result; the machine resolves only once both CONNECTED and the
 * remote identity payload have arrived.
 *
 * ## Endpoint filtering
 * A single [NearbyApi] may serve both roles at once (one loom, two machines in the
 * fake), so events are filtered by endpoint ID:
 * - non-null [endpointId] → only that endpoint's events (discoverer path);
 * - null [endpointId] → the FIRST [ConnectionInitiated] seen claims the endpoint
 *   (advertiser path), and subsequent events are filtered to it.
 */
internal class ConnectStateMachine(
    private val selfId: PeerId,
    private val api: NearbyApi,
    private val endpointId: String?,
    private val serviceId: String,
    private val timeoutMs: Long = 30_000L,
) {

    /**
     * Subscribe all handshake collectors (synchronously, via UNDISPATCHED),
     * invoke [trigger] to kick the handshake off, then suspend until the link
     * resolves or fails / times out.
     *
     * [trigger] is the role-specific kickoff: the discoverer calls
     * `requestConnection`; the advertiser passes a no-op (it has already started
     * advertising and merely awaits an incoming peer).
     */
    suspend fun run(
        scope: CoroutineScope,
        trigger: suspend () -> Unit,
    ): ConnectedLink =
        withTimeout(timeoutMs) {
            val deferred = CompletableDeferred<ConnectedLink>()
            val handshake = HandshakeState(initialEndpoint = endpointId)
            val jobs = launchListeners(scope, deferred, handshake)
            try {
                trigger()
                deferred.await()
            } finally {
                jobs.forEach { it.cancel() }
            }
        }

    private fun launchListeners(
        scope: CoroutineScope,
        deferred: CompletableDeferred<ConnectedLink>,
        handshake: HandshakeState,
    ): List<Job> =
        listOf(
            // Accept connection initiation when it arrives for our endpoint.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                api.connectionInitiated.collect { event ->
                    if (deferred.isCompleted) return@collect
                    if (!handshake.claimEndpoint(event.endpointId)) return@collect
                    api.acceptConnection(event.endpointId)
                }
            },
            // Observe connection result; on success send our identity.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
            },
            // Receive remote's identity payload (first BYTES for our endpoint).
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                api.payloadReceived.collect { event ->
                    if (deferred.isCompleted) return@collect
                    if (!handshake.isOurEndpoint(event.endpointId)) return@collect
                    if (handshake.remoteSelfId == null) {
                        handshake.remoteSelfId = PeerId(event.bytes.decodeToString())
                        handshake.maybeResolve(deferred)
                    }
                }
            },
        )

    private class HandshakeState(initialEndpoint: String?) {
        var endpoint: String? = initialEndpoint
            private set
        var connected: Boolean = false
        var remoteSelfId: PeerId? = null

        /**
         * Try to claim [candidate] as our target endpoint.
         * - With a target already set, returns true only if [candidate] matches.
         * - Without one (first-seen mode), claims it and returns true.
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
