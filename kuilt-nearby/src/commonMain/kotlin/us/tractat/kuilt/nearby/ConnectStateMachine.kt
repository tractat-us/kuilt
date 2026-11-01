package us.tractat.kuilt.nearby

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerIdentityRegistry
import us.tractat.kuilt.core.PeerIdentityRejectedException
import us.tractat.kuilt.core.PumpFailure
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.pumpIn
import kotlin.time.Duration

private val log = KotlinLogging.logger("us.tractat.kuilt.nearby.ConnectStateMachine")

/**
 * Drives the Nearby Connections request→initiate→accept→result handshake
 * for one side of a connection. Suspends until the live link is established
 * or throws on failure / timeout.
 *
 * ## [handshakeTimeout] bounds ONE connection, not a rendezvous
 * The ceiling covers exactly the span this machine drives. On the discoverer side that is from
 * `requestConnection`, on an endpoint it has **already found**, to `CONNECTED` plus the identity
 * payload. On the advertiser side the clock starts at `ConnectionInitiated`, because that is when
 * there is a connection to bound: waiting for a joiner to turn up is not a handshake, and a clock
 * started at the host's open silently stopped a host listening while it went on advertising (#2811).
 * Endpoint *discovery* happens in [NearbyLoom] before this machine is constructed and is deliberately
 * **not** covered, so this is not a `weaveTimeout` and must not be renamed to one — see
 * [NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT].
 *
 * Running out of time is a [ConnectionFailedException], not a `TimeoutCancellationException`:
 * [us.tractat.kuilt.core.Loom.weave] may let a cancellation out only for the caller's own cancel,
 * and a minted one would cancel that caller rather than fail it.
 *
 * ## A failed handshake leaves nothing behind
 * Whatever ends it short of a link — refusal, timeout, a throw from the radio, the caller's own
 * cancel — the id it bound is released and the endpoint it claimed is disconnected, so a link that
 * came up and then failed identity does not stay up with nobody listening to it. That is all a failed
 * attempt costs: whether anyone tries again is the caller's business ([NearbyLoom] does on the
 * advertiser side, and fails the `join` on the discoverer side).
 *
 * One exit has nothing to disconnect: a dial the radio itself turned down. Nothing of this machine's
 * is on that endpoint, and something else's may be, since the radio answers
 * `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` for the link this loom's own host took from that device. A
 * dial that was only cancelled is different, by [radioCallMayStillBeRunning], and is still released.
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
 * endpoint never becomes a peer, so nothing downstream — [NearbyLoom]'s `weavePeers`, the seam's
 * `endpointPeers`, a `sendTo` target — can ever name it.
 *
 * ## Endpoint filtering
 * A single [NearbyApi] may serve both roles at once (one loom, two machines in the
 * fake), so events are filtered by endpoint ID:
 * - non-null [endpointId] → only that endpoint's events (discoverer path);
 * - null [endpointId] → the FIRST [ConnectionInitiated] that [mayClaim] allows claims the
 *   endpoint (advertiser path), and subsequent events are filtered to it.
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
    /**
     * Whether the advertiser path may claim this endpoint, asked at the moment of the claim. One radio
     * reports incoming joiners and this loom's own outgoing dials through the same callback, with
     * nothing in the event to tell them apart, so only the loom can say which this one is. The
     * discoverer path already knows its endpoint and never asks.
     */
    private val mayClaim: (String) -> Boolean = { true },
) {

    /**
     * Subscribe all handshake collectors (synchronously, via UNDISPATCHED), wait for there to be a
     * handshake at all, then suspend until the link resolves or fails / times out.
     *
     * [trigger] is the role-specific kickoff: the discoverer calls `requestConnection`, and already
     * knows its endpoint, so its clock starts here; the advertiser passes a no-op (it has already
     * started advertising) and suspends **without a bound** until a peer initiates, which is what
     * starts its clock.
     */
    suspend fun run(
        scope: CoroutineScope,
        trigger: suspend () -> Unit,
    ): ConnectedLink {
        val deferred = CompletableDeferred<ConnectedLink>()
        val claimed = CompletableDeferred<String>()
        endpointId?.let { claimed.complete(it) }
        val handshake = HandshakeState(initialEndpoint = endpointId, mayClaim = mayClaim)
        val jobs = launchListeners(scope, deferred, claimed, handshake)
        var resolved = false
        try {
            val endpoint = claimed.await()
            val link =
                withTimeoutOrNull(handshakeTimeout) {
                    kickOff(trigger, handshake)
                    deferred.await()
                } ?: throw ConnectionFailedException(endpoint, "no handshake within $handshakeTimeout")
            resolved = true
            return link
        } finally {
            jobs.forEach { it.cancel() }
            // Only the resolving path hands the binding and the link on. Anything else must leave
            // neither behind: a stale binding would make the id look taken to the next endpoint that
            // legitimately announces it, and a link nobody reads stays up on the radio.
            if (!resolved) abandon(handshake)
        }
    }

    private suspend fun kickOff(trigger: suspend () -> Unit, handshake: HandshakeState) {
        try {
            trigger()
        } catch (failure: Throwable) {
            if (!failure.radioCallMayStillBeRunning()) handshake.markDialRefused()
            throw failure
        }
    }

    /**
     * Release what a failed handshake holds. Shielded, because the reason this runs may be this
     * coroutine's own cancellation and `disconnect` suspends, with a plain `catch` because inside the
     * shield a cancellation can only be one `disconnect` minted, and rethrowing it would replace the
     * failure being reported with a bare cancellation.
     */
    private suspend fun abandon(handshake: HandshakeState) {
        val endpoint = handshake.abandon(registry) ?: return
        withContext(NonCancellable) {
            try {
                api.disconnect(endpoint)
            } catch (failure: Throwable) {
                log.debug { "nearby.handshake.release-failed endpoint=$endpoint failure=$failure" }
            }
        }
    }

    private fun launchListeners(
        scope: CoroutineScope,
        deferred: CompletableDeferred<ConnectedLink>,
        claimed: CompletableDeferred<String>,
        handshake: HandshakeState,
    ): List<Job> {
        fun <T> Flow<T>.listen(name: String, body: suspend (T) -> Unit): Job =
            pumpIn(
                scope = scope,
                onFailure = { failure, cause -> failHandshake(name, failure, cause, deferred, claimed, handshake) },
                name = "nearby-handshake-$name[${selfId.value}]",
                start = CoroutineStart.UNDISPATCHED,
                body = body,
            )

        return listOf(
            // Accept connection initiation when it arrives for our endpoint.
            api.connectionInitiated.listen("initiated") { event ->
                if (!deferred.isCompleted && handshake.claimEndpoint(event.endpointId)) {
                    claimed.complete(event.endpointId) // before the accept, so the clock covers it
                    api.acceptConnection(event.endpointId)
                }
            },
            // Observe connection result; on success send our identity.
            api.connectionResult.listen("result") { event ->
                if (!deferred.isCompleted && handshake.isOurEndpoint(event.endpointId)) {
                    if (event.success) {
                        api.sendBytesPayload(event.endpointId, selfId.value.encodeToByteArray())
                        // Only now: resolving cancels this collector, and a send still in flight with it.
                        handshake.markConnected()?.let { deferred.complete(it) }
                    } else {
                        deferred.completeExceptionally(
                            ConnectionFailedException(event.endpointId, event.reason),
                        )
                    }
                }
            },
            // Receive remote's identity payload (first BYTES for our endpoint).
            api.payloadReceived.listen("identity") { event ->
                if (!deferred.isCompleted && handshake.isOurEndpoint(event.endpointId)) {
                    admitAnnouncedIdentity(event, handshake, deferred)
                }
            },
        )
    }

    /**
     * A listener failed, which fails this handshake. An item failure (the radio threw on one call,
     * possibly a cancellation it minted) becomes a [ConnectionFailedException] and costs one attempt.
     * A failed *stream* is [NearbyEventStreamLostException], since no later attempt could see anything
     * either, and it also wakes an advertiser that has not claimed anybody yet.
     */
    private fun failHandshake(
        listener: String,
        failure: PumpFailure,
        cause: Throwable,
        deferred: CompletableDeferred<ConnectedLink>,
        claimed: CompletableDeferred<String>,
        handshake: HandshakeState,
    ): PumpVerdict {
        log.warn(cause) { "nearby.handshake.listener-failed listener=$listener kind=$failure" }
        when (failure) {
            PumpFailure.ITEM ->
                deferred.completeExceptionally(
                    ConnectionFailedException(handshake.endpointOrUnclaimed(), "$listener failed: $cause"),
                )
            PumpFailure.UPSTREAM -> {
                val lost = NearbyEventStreamLostException(listener, cause)
                claimed.completeExceptionally(lost)
                deferred.completeExceptionally(lost)
            }
        }
        return PumpVerdict.STOP
    }

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
        if (handshake.hasIdentity()) return
        val announced = decodeAnnouncedId(event.bytes)
        if (announced == null) {
            deferred.completeExceptionally(
                PeerIdentityRejectedException(endpoint, "identity payload is not valid UTF-8"),
            )
            return
        }
        when (val admission = handshake.bindIdentity(announced, endpoint, registry)) {
            is IdentityAdmission.Admitted -> admission.link?.let { deferred.complete(it) }
            is IdentityAdmission.Refused ->
                deferred.completeExceptionally(
                    PeerIdentityRejectedException(endpoint, "announced id refused: ${admission.outcome}"),
                )
            IdentityAdmission.Abandoned -> Unit
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

    private sealed interface IdentityAdmission {
        /** Bound; [link] is non-null if that was the last thing the handshake was waiting for. */
        data class Admitted(val link: ConnectedLink?) : IdentityAdmission

        data class Refused(val outcome: PeerIdentityRegistry.BindResult) : IdentityAdmission

        /** The handshake is already over, so nothing was bound. */
        data object Abandoned : IdentityAdmission
    }

    /**
     * One handshake's progress. Three listeners write it, each on whatever thread its flow resumes on,
     * so every field is guarded by [lock]: a `connected` written on one thread and an identity written
     * on another must not each read the other as unset, because neither would then resolve and a
     * healthy handshake would run out its clock. Nothing under the lock suspends.
     */
    private class HandshakeState(
        initialEndpoint: String?,
        private val mayClaim: (String) -> Boolean,
    ) {
        private val lock = reentrantLock()
        private var endpoint: String? = initialEndpoint
        private var connected: Boolean = false

        /**
         * The remote's admitted identity — set only by [bindIdentity], i.e. only once the shared
         * [PeerIdentityRegistry] has accepted it. A non-null value therefore means "this endpoint
         * holds this id in the registry", which is what [abandon] relies on to undo it.
         */
        private var remoteSelfId: PeerId? = null

        /**
         * Set by [abandon] and read by everything that could still add to this handshake, because
         * cancelling a listener does not stop one already past its checks: without this, an identity
         * bound just after the release would stay bound for good.
         */
        private var abandoned: Boolean = false

        /** The radio turned the dial down, so the endpoint is not this handshake's to disconnect. */
        private var dialRefused: Boolean = false

        fun markDialRefused() {
            lock.withLock { dialRefused = true }
        }

        fun endpointOrUnclaimed(): String = lock.withLock { endpoint ?: "unclaimed" }

        fun hasIdentity(): Boolean = lock.withLock { remoteSelfId != null }

        fun isOurEndpoint(candidate: String): Boolean = lock.withLock { endpoint == candidate }

        /**
         * Try to claim [candidate] as our target endpoint.
         * - With a target already set, returns true only if [candidate] matches.
         * - Without one (first-seen mode), claims it if [mayClaim] agrees.
         */
        fun claimEndpoint(candidate: String): Boolean =
            lock.withLock {
                if (abandoned) return false
                val current = endpoint
                if (current != null) return current == candidate
                if (!mayClaim(candidate)) return false
                endpoint = candidate
                true
            }

        /** Record that the link is up and our identity has gone out; the link, if that completes it. */
        fun markConnected(): ConnectedLink? =
            lock.withLock {
                connected = true
                resolvedLink()
            }

        /**
         * Put [announced] to the registry and record the answer, as one step, so that [abandon] either
         * sees this binding or stops it from happening.
         */
        fun bindIdentity(
            announced: PeerId,
            endpoint: String,
            registry: PeerIdentityRegistry<String>,
        ): IdentityAdmission =
            lock.withLock {
                if (abandoned) return IdentityAdmission.Abandoned
                when (val outcome = registry.bind(announced, endpoint)) {
                    // ALREADY_BOUND is this same endpoint re-announcing the id it already holds — the
                    // [hasIdentity] guard makes that unreachable today, and it is admitted rather than
                    // refused so a duplicate payload could never fail a healthy handshake.
                    PeerIdentityRegistry.BindResult.BOUND,
                    PeerIdentityRegistry.BindResult.ALREADY_BOUND,
                    -> {
                        remoteSelfId = announced
                        IdentityAdmission.Admitted(resolvedLink())
                    }
                    else -> IdentityAdmission.Refused(outcome)
                }
            }

        /**
         * End this handshake: give the id back if it bound one, identity-scoped through
         * [PeerIdentityRegistry.unbind] so it can only ever release *this* endpoint's own binding, and
         * return the endpoint it holds, if any, for the caller to disconnect.
         */
        fun abandon(registry: PeerIdentityRegistry<String>): String? =
            lock.withLock {
                abandoned = true
                val claimedEndpoint = endpoint
                val bound = remoteSelfId
                if (bound != null && claimedEndpoint != null) registry.unbind(bound, claimedEndpoint)
                remoteSelfId = null
                claimedEndpoint.takeUnless { dialRefused }
            }

        // Called with [lock] held.
        private fun resolvedLink(): ConnectedLink? {
            val claimedEndpoint = endpoint
            val identity = remoteSelfId
            return if (connected && claimedEndpoint != null && identity != null) {
                ConnectedLink(claimedEndpoint, identity)
            } else {
                null
            }
        }
    }
}

/**
 * Whether a [NearbyApi] call that threw this may still be running on the radio, and so may yet take
 * effect. A failure means the radio answered no. A cancellation means nobody heard an answer, which is
 * what the Play services binding does when cancelled: it stops awaiting a Task, and the Task carries on.
 */
internal fun Throwable.radioCallMayStillBeRunning(): Boolean = this is CancellationException

/** A resolved, live Nearby connection. */
internal data class ConnectedLink(
    val endpointId: String,
    val remotePeerId: PeerId,
)

/** Thrown when Nearby reports a connection failure, or a handshake does not finish in time. */
public class ConnectionFailedException(
    endpointId: String,
    reason: String?,
) : Exception("Connection to $endpointId failed: $reason")

/**
 * One of [NearbyApi]'s event streams has itself failed, so nothing further can be heard on it: no
 * handshake can complete on this radio, and trying again would only fail the same way, at once.
 */
internal class NearbyEventStreamLostException(
    listener: String,
    cause: Throwable,
) : Exception("the Nearby $listener event stream failed: $cause", cause)
