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
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger("us.tractat.kuilt.nw.NwLoom")

/**
 * Thrown by [NwLoom.weave] when no peer is reached within the weave timeout — the fabric never wove.
 *
 * A plain [Exception], deliberately **not** a [kotlin.coroutines.cancellation.CancellationException]:
 * the earlier code rethrew the raw [TimeoutCancellationException], so a caller wrapping [NwLoom.weave]
 * in `runCatchingCancellable` saw it as its OWN structured cancellation and rethrew — an unreachable
 * fabric masqueraded as caller cancellation. This distinct type lets callers catch a fabric failure.
 */
public class NwUnreachableException(message: String) : Exception(message)

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
 * [weaveTimeout] the seam is closed [CloseReason.Unreachable] and [weave] throws
 * [NwUnreachableException] — a plain exception, NOT a `CancellationException`, so a caller wrapping
 * `weave` in `runCatchingCancellable` sees a fabric failure rather than its own cancellation.
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
 * @param random      source of the seam's per-connection dedup nonces; production defaults to
 *   [Random.Default], tests inject a seeded [Random] for a deterministic dedup tiebreak.
 * @param weaveTimeout how long [weave] waits for the first peer before throwing
 *   [NwUnreachableException] (default [DEFAULT_WEAVE_TIMEOUT]); injectable for tests.
 */
public class NwLoom(
    private val api: NwApi,
    private val serviceType: String,
    public val selfId: PeerId = freshPeerId(),
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val random: Random = Random.Default,
    private val weaveTimeout: Duration = DEFAULT_WEAVE_TIMEOUT,
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
        val seam = NwSeam(selfId, api, seamScope, random, policy)

        val serviceName = when (rendezvous) {
            is Rendezvous.New -> rendezvous.pattern.sessionName
            is Rendezvous.Existing -> selfId.value
        }
        log.debug { "nw.loom.weave self=${selfId.value} serviceType=$serviceType serviceName=$serviceName rendezvous=${rendezvous::class.simpleName}" }

        // Subscribe to discovery BEFORE advertising/browsing (subscribe-before-trigger: the API's
        // flows are hot with no replay). UNDISPATCHED so the collector is live before the first emit.
        // Auto-dial every newly-discovered endpoint exactly once (endpoint-level dedup); the
        // cross-peer double-dial is collapsed by NwSeam's connection dedup.
        val dialed = mutableSetOf<String>()
        seamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.endpointFound.collect { endpoint ->
                // Drop this loom's OWN endpoint. A device that advertises AND browses the same type is
                // delivered its own advertisement by real Bonjour/mDNS (and by FakeNwRadio, #1485). For
                // Rendezvous.Existing this loom advertised serviceName = selfId.value, and selfIds are
                // distinct UUIDs, so only self carries it — a cheap endpoint-level self-identity. Skip it
                // entirely: neither surface it in the lobby roster (a self-ghost in "wait for a friend")
                // nor dial it. (For Rendezvous.New the serviceName is the shared session name, so self is
                // indistinguishable here and the NwSeam self-connection guard remains the backstop.)
                if (endpoint.serviceName == selfId.value) {
                    log.debug { "nw.loom.self-skip endpoint=${endpoint.id} self=${selfId.value}" }
                    return@collect
                }
                _visiblePeers.update { it + endpoint }
                val firstSight = dialed.add(endpoint.id)
                log.debug { "nw.loom.discovered endpoint=${endpoint.id} self=${selfId.value} visible=${_visiblePeers.value.map { it.id }}${if (firstSight) " → dialing" else " (already dialed)"}" }
                if (firstSight) {
                    runCatchingCancellable { api.connect(endpoint) }
                        .onFailure { log.debug { "nw.loom.dial-failed endpoint=${endpoint.id} self=${selfId.value}: ${it.message}" } }
                }
            }
        }

        runCatchingCancellable { api.startListening(serviceName, serviceType) }
            .onFailure { log.debug { "nw.listen failed serviceName=$serviceName selfId=${selfId.value}" } }
        runCatchingCancellable { api.startBrowsing(serviceType) }
            .onFailure { log.debug { "nw.browse failed serviceType=$serviceType selfId=${selfId.value}" } }

        // Await the first resolved remote so the returned seam is already connected. On timeout the
        // fabric never wove — close Unreachable and throw NwUnreachableException (NOT the raw
        // TimeoutCancellationException, which a runCatchingCancellable caller would mistake for its
        // own cancellation and rethrow).
        try {
            withTimeout(weaveTimeout) {
                seam.peers.first { it.size > 1 }
            }
        } catch (_: TimeoutCancellationException) {
            log.info { "nw.loom.weave-timeout self=${selfId.value} serviceType=$serviceType after=$weaveTimeout → Unreachable" }
            seam.close(CloseReason.Unreachable)
            throw NwUnreachableException(
                "nw weave timed out: no peer reached for serviceType=$serviceType within $weaveTimeout",
            )
        }
        log.debug { "nw.loom.wove self=${selfId.value} peers=${seam.peers.value.map { it.value }} state=${seam.state.value}" }
        return seam
    }

    public companion object {
        /** How long [weave] waits for the first peer before declaring the fabric [CloseReason.Unreachable]. */
        public val DEFAULT_WEAVE_TIMEOUT: Duration = 30.seconds
    }
}
