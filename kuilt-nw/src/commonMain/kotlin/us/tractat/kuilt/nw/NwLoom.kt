package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger("us.tractat.kuilt.nw.NwLoom")

/**
 * Full-mesh [Loom] over an [NwApi] — Apple Network.framework's peer-to-peer fabric.
 *
 * ## Symmetric full mesh (advertise + browse + dial)
 * There is no client/server split. Both roles do exactly the same thing on [weave]:
 * **advertise** ([NwApi.startListening]) so peers can find this one, **browse**
 * ([NwApi.startBrowsing]) to find other peers, and **auto-dial** ([NwApi.connect]) every
 * discovered endpoint. Each unordered pair therefore double-dials (both ends dial); the
 * redundant connection is deduplicated by [NwSeam] (lower-id dialer wins). Discovery is by
 * Bonjour [serviceType], so the advertised *service name* does not gate who connects — it is
 * only a human-readable label: [Rendezvous.New] advertises the session name, [Rendezvous.Existing]
 * advertises this peer's own [selfId].
 *
 * ## UUID self-identity (#1405)
 * [selfId] defaults to a fresh random UUID via [freshPeerId], so two devices never mint the same
 * identity and collide the instant they meet — unlike a per-loom monotonic counter.
 *
 * ## Await-first-peer
 * [weave] does not return until the seam has resolved its first remote peer (mirrors the role-split
 * conformance contract: `host()`/`join()` both return *connected* seams). If no peer resolves within
 * [DEFAULT_WEAVE_TIMEOUT] the seam is closed [CloseReason.Unreachable] and the timeout rethrows.
 *
 * ## Scope
 * Background collectors (the seam's three loops + this loom's discovery/dial loop) run on
 * `CoroutineScope(currentCoroutineContext() + SupervisorJob())`, inheriting the caller's dispatcher
 * (so tests keep virtual time) with an independent [SupervisorJob] cancelled when the seam is closed.
 *
 * @param api         the Network.framework binding (real `RealNwApi`, or `FakeNwApi` under test).
 * @param serviceType the Bonjour service type both advertised and browsed; peers with the same type meet.
 * @param selfId      this peer's stable identity; defaults to a fresh random UUID ([freshPeerId], #1405).
 * @param policy      inbound delivery policy for each woven [Seam] (default [DeliveryPolicy.Reliable]).
 */
public class NwLoom(
    private val api: NwApi,
    private val serviceType: String,
    public val selfId: PeerId = freshPeerId(),
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Loom {

    private val _visiblePeers = MutableStateFlow<Set<NwEndpoint>>(emptySet())

    /**
     * Every endpoint this loom has discovered while browsing, accumulated across the session.
     * Intended for a lobby view (Phase 5) — it is a *discovery* roster, distinct from a woven
     * [Seam.peers] set (resolved, connected identities).
     */
    public val visiblePeers: StateFlow<Set<NwEndpoint>> = _visiblePeers.asStateFlow()

    override fun availability(): FabricAvailability = api.availability()

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        // Derive from the caller so background work runs on the test dispatcher; independent Job
        // so seam close() cancels only this session's coroutines.
        val seamScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val seam = NwSeam(selfId, api, seamScope, policy)

        val serviceName = when (rendezvous) {
            is Rendezvous.New -> rendezvous.pattern.sessionName
            is Rendezvous.Existing -> selfId.value
        }

        // Subscribe to discovery BEFORE advertising/browsing (subscribe-before-trigger: the API's
        // flows are hot with no replay). UNDISPATCHED so the collector is live before the first emit.
        // Auto-dial every newly-discovered endpoint exactly once (endpoint-level dedup); the
        // cross-peer double-dial is collapsed by NwSeam's connection dedup.
        val dialed = mutableSetOf<String>()
        seamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.endpointFound.collect { endpoint ->
                _visiblePeers.update { it + endpoint }
                if (dialed.add(endpoint.id)) {
                    runCatchingCancellable { api.connect(endpoint) }
                        .onFailure { log.debug { "nw.dial failed endpoint=${endpoint.id} selfId=${selfId.value}" } }
                }
            }
        }

        runCatchingCancellable { api.startListening(serviceName, serviceType) }
            .onFailure { log.debug { "nw.listen failed serviceName=$serviceName selfId=${selfId.value}" } }
        runCatchingCancellable { api.startBrowsing(serviceType) }
            .onFailure { log.debug { "nw.browse failed serviceType=$serviceType selfId=${selfId.value}" } }

        // Await the first resolved remote so the returned seam is already connected. On timeout the
        // fabric never wove — close Unreachable and rethrow (mirror the Loom contract for a dead dial).
        try {
            withTimeout(DEFAULT_WEAVE_TIMEOUT) {
                seam.peers.first { it.size > 1 }
            }
        } catch (e: TimeoutCancellationException) {
            seam.close(CloseReason.Unreachable)
            throw e
        }
        return seam
    }

    public companion object {
        /** How long [weave] waits for the first peer before declaring the fabric [CloseReason.Unreachable]. */
        public val DEFAULT_WEAVE_TIMEOUT: Duration = 30.seconds
    }
}
