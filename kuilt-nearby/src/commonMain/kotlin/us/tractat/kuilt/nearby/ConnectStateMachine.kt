package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerIdentityRegistry
import us.tractat.kuilt.core.PeerIdentityRejectedException
import kotlin.time.Duration

/**
 * Drives the Nearby Connections request→initiate→accept→result handshake
 * for one side of a connection. Suspends until the live link is established
 * or throws on failure / timeout.
 *
 * ## [handshakeTimeout] bounds ONE connection, not a rendezvous
 * The ceiling covers exactly the span this machine drives: kick off (`requestConnection`
 * on an endpoint the discoverer has **already found**, or nothing at all on the advertiser
 * side) through `CONNECTED` plus the identity payload. Endpoint *discovery* happens in
 * [NearbyLoom] before this machine is constructed and is deliberately **not** covered, so
 * this is not a `weaveTimeout` and must not be renamed to one — see
 * [NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT].
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
 * ## Those bytes are peer-supplied, and this is where they are checked (#1821)
 * The identity payload is the first thing a stranger's radio hands this process, and it used to
 * become a [PeerId] with no check at all: `PeerId(event.bytes.decodeToString())`. Two things now
 * stand between the bytes and the roster, and they are deliberately different in kind:
 *  - **decoding** is this machine's own business — [decodeAnnouncedId] decodes strictly, so bytes
 *    that are not valid UTF-8 fail the handshake instead of being folded lossily onto U+FFFD (the
 *    default substitutes the replacement character, so two *distinct* invalid announcements
 *    silently became one identical id);
 *  - **admission** is not — a blank id, this peer's own [PeerId] and an id another endpoint already
 *    holds are refused by the shared [PeerIdentityRegistry], the same rules `:kuilt-multipeer`'s
 *    two links are held to, rather than a third hand-rolled approximation of them.
 *
 * A refusal completes the handshake exceptionally with [PeerIdentityRejectedException]: the
 * endpoint never becomes a peer, so nothing downstream — [NearbyLoom]'s `sharedPeers`, the seam's
 * `endpointPeers`, a `sendTo` target — can ever name it.
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
    private val handshakeTimeout: Duration,
    /**
     * The weave's peer-identity authority, keyed by Nearby endpoint ID. Required, not defaulted:
     * a fresh registry per machine would still refuse a blank or self id, but it would answer the
     * *collision* question against an empty roster and so could not see an id a sibling endpoint on
     * the same weave already holds. The one shared instance is what makes the answer true.
     */
    private val registry: PeerIdentityRegistry<String>,
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
        withTimeout(handshakeTimeout) {
            val deferred = CompletableDeferred<ConnectedLink>()
            val handshake = HandshakeState(initialEndpoint = endpointId)
            val jobs = launchListeners(scope, deferred, handshake)
            var resolved = false
            try {
                trigger()
                deferred.await().also { resolved = true }
            } finally {
                jobs.forEach { it.cancel() }
                // A handshake that failed, timed out or was cancelled AFTER the identity payload
                // bound its id must not leave that binding behind: the endpoint never became a
                // peer, and a stale binding would make the id look taken to the next endpoint that
                // legitimately announces it. Only the resolving path hands the binding on.
                if (!resolved) handshake.releaseBinding(registry)
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
                        val endpoint = requireNotNull(handshake.endpoint) {
                            "connection result for our endpoint but no claimed endpoint set"
                        }
                        api.sendBytesPayload(endpoint, selfId.value.encodeToByteArray())
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
                        admitAnnouncedIdentity(event, handshake, deferred)
                    }
                }
            },
        )

    /**
     * Turn the remote's announced bytes into an admitted [PeerId], or fail the handshake.
     *
     * Both refusals land on the same [PeerIdentityRejectedException] because the caller's remedy is
     * the same — this endpoint is not a peer — but the [PeerIdentityRejectedException.reason]
     * distinguishes them, so a log line says which rule fired.
     */
    private fun admitAnnouncedIdentity(
        event: PayloadReceived,
        handshake: HandshakeState,
        deferred: CompletableDeferred<ConnectedLink>,
    ) {
        val endpoint = event.endpointId
        val announced = decodeAnnouncedId(event.bytes)
        if (announced == null) {
            deferred.completeExceptionally(
                PeerIdentityRejectedException(endpoint, "identity payload is not valid UTF-8"),
            )
            return
        }
        when (val outcome = registry.bind(announced, endpoint)) {
            // ALREADY_BOUND is this same endpoint re-announcing the id it already holds — the
            // `remoteSelfId == null` guard above makes that unreachable today, and it is admitted
            // rather than refused so a duplicate payload could never fail a healthy handshake.
            PeerIdentityRegistry.BindResult.BOUND,
            PeerIdentityRegistry.BindResult.ALREADY_BOUND,
            -> {
                handshake.bind(announced)
                handshake.maybeResolve(deferred)
            }
            else ->
                deferred.completeExceptionally(
                    PeerIdentityRejectedException(endpoint, "announced id refused: $outcome"),
                )
        }
    }

    /**
     * Decode the announced identity **strictly**, returning `null` on bytes that are not valid
     * UTF-8.
     *
     * `ByteArray.decodeToString()` defaults to lossy decoding: every malformed sequence becomes
     * U+FFFD, so `0xC3` alone and `0x80` alone — two entirely different announcements — both decode
     * to the *same* one-character id. That is a silent merge of two devices onto one identity, which
     * is the half of the #1466 class a collision check downstream cannot see, because by then there
     * is only one id.
     */
    private fun decodeAnnouncedId(bytes: ByteArray): PeerId? =
        try {
            PeerId(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: CharacterCodingException) {
            null
        }

    private class HandshakeState(initialEndpoint: String?) {
        var endpoint: String? = initialEndpoint
            private set
        var connected: Boolean = false

        /**
         * The remote's admitted identity — set only by [bind], i.e. only once the shared
         * [PeerIdentityRegistry] has accepted it. A non-null value therefore means "this endpoint
         * holds this id in the registry", which is what [releaseBinding] relies on to undo it.
         */
        var remoteSelfId: PeerId? = null
            private set

        /** Record the id this endpoint was just admitted under. */
        fun bind(admitted: PeerId) {
            remoteSelfId = admitted
        }

        /**
         * Give the id back if this handshake bound one and then failed. A no-op when nothing was
         * bound, and identity-scoped through [PeerIdentityRegistry.unbind], so it can only ever
         * release *this* endpoint's own binding.
         */
        fun releaseBinding(registry: PeerIdentityRegistry<String>) {
            val bound = remoteSelfId ?: return
            val ep = endpoint ?: return
            registry.unbind(bound, ep)
            remoteSelfId = null
        }

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
            val resolvedRemoteSelfId = remoteSelfId
            if (connected && resolvedRemoteSelfId != null) {
                val resolvedEndpoint = requireNotNull(endpoint) {
                    "resolving link while connected but no claimed endpoint set"
                }
                deferred.complete(ConnectedLink(resolvedEndpoint, resolvedRemoteSelfId))
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
