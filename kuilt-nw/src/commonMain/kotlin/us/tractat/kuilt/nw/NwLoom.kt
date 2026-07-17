package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.time.Duration.Companion.milliseconds
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
 * ## Redial with unbounded backoff (#1513)
 * Dialling is not one-shot. A [RedialCoordinator] keeps an outstanding dial for every
 * discovered-but-not-currently-connected endpoint, backing off exponentially
 * ([INITIAL_REDIAL_BACKOFF] → double → ceiling [MAX_REDIAL_BACKOFF]) with jitter drawn from the
 * loom's injected [random], **unbounded** (no attempt cap) for as long as the seam is open. It reads
 * [NwSeam.settledEndpoints] to know which endpoints are already reached (a connected peer's endpoint,
 * or a self-resolved endpoint) and dials only the complement; the moment an endpoint settles the loop
 * parks, and the moment its peer drops (the seam re-forms to [us.tractat.kuilt.core.SeamState.Weaving],
 * #1513) it redials. A fresh `endpointFound` sighting resets that endpoint's backoff. The whole
 * mechanism lives on the seam scope, so [Seam.close] cancels it.
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
 *   [NwUnreachableException] (default [DEFAULT_WEAVE_TIMEOUT], 30s). Injectable (#1447 item 1) so a
 *   "wait for a friend" lobby can pass a generous value and hold the session open far longer than the
 *   default; deliberately NOT infinite by default. Tests inject a small value.
 * @param wovenPathGrace how long a path-lost (`ready → waiting`) connection is given to recover before
 *   the woven seam tears it as [CloseReason.Unreachable] (#1478); default [NwSeam.DEFAULT_WOVEN_PATH_GRACE]
 *   (10s), injectable for tests.
 */
public class NwLoom(
    private val api: NwApi,
    private val serviceType: String,
    public val selfId: PeerId = freshPeerId(),
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val random: Random = Random.Default,
    private val weaveTimeout: Duration = DEFAULT_WEAVE_TIMEOUT,
    private val wovenPathGrace: Duration = NwSeam.DEFAULT_WOVEN_PATH_GRACE,
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
        val seam = NwSeam(selfId, api, seamScope, random, policy, wovenPathGrace)

        val serviceName = when (rendezvous) {
            is Rendezvous.New -> rendezvous.pattern.sessionName
            is Rendezvous.Existing -> selfId.value
        }
        log.debug { "nw.loom.weave self=${selfId.value} serviceType=$serviceType serviceName=$serviceName rendezvous=${rendezvous::class.simpleName}" }

        // Subscribe to discovery BEFORE advertising/browsing (subscribe-before-trigger: the API's flows
        // are hot with no replay). The coordinator's endpointFound collector is UNDISPATCHED so it is live
        // before the first emit; it drives redial with backoff (#1513) instead of a one-shot dial.
        val redial = RedialCoordinator(
            api = api,
            seam = seam,
            scope = seamScope,
            selfId = selfId,
            random = random,
            onDiscovered = { endpoint -> _visiblePeers.update { it + endpoint } },
        )
        redial.start()

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

        /** First redial backoff after a dial that has not (yet) connected (#1513). */
        internal val INITIAL_REDIAL_BACKOFF: Duration = 250.milliseconds

        /** Ceiling the redial backoff doubles up to — a gone endpoint's dials keep failing fast here (#1513). */
        internal val MAX_REDIAL_BACKOFF: Duration = 5.seconds
    }
}

/**
 * Keeps an outstanding dial for every discovered-but-not-currently-connected endpoint, with unbounded
 * exponential backoff, for as long as the seam is open (#1513). One redial coroutine per endpoint loops:
 * while the endpoint is NOT in [NwSeam.settledEndpoints] it dials and backs off (doubling up to
 * [NwLoom.MAX_REDIAL_BACKOFF], with jitter from [random]); once it settles the loop parks on the flow and
 * wakes only when the endpoint's peer drops (the seam re-forms to Weaving). A fresh `endpointFound`
 * sighting resets that endpoint's backoff. Every coroutine runs on the seam [scope], so [Seam.close]
 * cancels them all.
 *
 * ## Why key on [NwSeam.settledEndpoints], not on this loom's own connections
 * The full-mesh double-dial means the dedup loser's `connectionClosed` fires for an endpoint whose peer is
 * still reached via the *surviving* (inbound) link. Keyed on our own outbound connection liveness, that
 * close would provoke an endless redial→dedup→close storm. The seam resolves each endpoint to a stable
 * [PeerId] and reports an endpoint as settled while its peer is connected (learned from whichever link
 * carried the endpoint), so the coordinator dials only genuinely-unreached endpoints — no storm.
 *
 * ## Thread-safety
 * The [redialers] map and each entry's mutable `backoffMs`/`job` are read-modify-written only under
 * [lock] (atomicfu). No suspend call ([NwApi.connect], [delay], flow collection) runs under the lock:
 * each iteration snapshots what it needs under the lock and acts outside it. Correct under a
 * multi-threaded dispatcher; no single-thread-confinement crutch.
 */
private class RedialCoordinator(
    private val api: NwApi,
    private val seam: NwSeam,
    private val scope: CoroutineScope,
    private val selfId: PeerId,
    private val random: Random,
    private val onDiscovered: (NwEndpoint) -> Unit,
) {
    private val lock = reentrantLock()

    private class Redialer(val endpoint: NwEndpoint) {
        var backoffMs: Long = NwLoom.INITIAL_REDIAL_BACKOFF.inWholeMilliseconds
        var job: Job? = null
    }

    /** endpoint id → its redial state. Guarded by [lock]. */
    private val redialers = mutableMapOf<String, Redialer>()

    /** Subscribe to discovery UNDISPATCHED (before advertise/browse) so no sighting is missed. */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.endpointFound.collect { onEndpointFound(it) }
        }
    }

    private fun onEndpointFound(endpoint: NwEndpoint) {
        // Drop this loom's OWN endpoint. A device that advertises AND browses the same type is delivered
        // its own advertisement by real Bonjour/mDNS (and by FakeNwRadio, #1485). For Rendezvous.Existing
        // this loom advertised serviceName = selfId.value (distinct UUIDs), so only self carries it — skip
        // it entirely (no self-ghost in the lobby, no self-redial). For Rendezvous.New the serviceName is
        // the shared session name, so self is indistinguishable here; the redial loop still parks once the
        // NwSeam self-connection guard resolves it to selfId and marks the endpoint settled.
        if (endpoint.serviceName == selfId.value) {
            log.debug { "nw.loom.self-skip endpoint=${endpoint.id} self=${selfId.value}" }
            return
        }
        onDiscovered(endpoint)
        val armed = lock.withLock {
            val r = redialers.getOrPut(endpoint.id) { Redialer(endpoint) }
            r.backoffMs = NwLoom.INITIAL_REDIAL_BACKOFF.inWholeMilliseconds // reset backoff on (fresh) sighting
            if (r.job?.isActive == true) {
                false // already redialing this endpoint
            } else {
                // Eager launch: the body is dispatched (never runs inline under this lock — no suspend under
                // the lock), and `isActive` is true the instant we assign it, so a concurrent sighting in the
                // launch→assign window can't spawn a duplicate loop.
                r.job = scope.launch { redialLoop(endpoint.id) }
                true
            }
        }
        log.debug { "nw.loom.discovered endpoint=${endpoint.id} self=${selfId.value}${if (armed) " → redial armed" else " (already redialing)"}" }
    }

    private suspend fun redialLoop(endpointId: String) {
        while (true) {
            val endpoint = lock.withLock { redialers[endpointId]?.endpoint } ?: return
            // Already settled (its peer is connected, or it resolved to self) → park until it un-settles,
            // then start a fresh campaign at the initial backoff.
            if (endpointId in seam.settledEndpoints.value) {
                seam.settledEndpoints.first { endpointId !in it }
                lock.withLock { redialers[endpointId]?.backoffMs = NwLoom.INITIAL_REDIAL_BACKOFF.inWholeMilliseconds }
                continue
            }
            runCatchingCancellable { api.connect(endpoint) }
                .onFailure { log.debug { "nw.loom.redial-failed endpoint=$endpointId self=${selfId.value}: ${it.message}" } }
            val waitMs = lock.withLock {
                val r = redialers[endpointId] ?: return
                r.backoffMs.also { r.backoffMs = (r.backoffMs * 2).coerceAtMost(NwLoom.MAX_REDIAL_BACKOFF.inWholeMilliseconds) }
            }
            val jitterMs = random.nextLong(0, (waitMs / 4).coerceAtLeast(1))
            // Wait the backoff, but wake the instant the endpoint settles so we stop dialing promptly.
            withTimeoutOrNull((waitMs + jitterMs).milliseconds) {
                seam.settledEndpoints.first { endpointId in it }
            }
        }
    }
}
