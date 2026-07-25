package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
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
     * The endpoints this loom currently sees while browsing — a live *discovery* roster for a lobby view
     * (Phase 5), distinct from a woven [Seam.peers] set (resolved, connected identities). An endpoint is
     * added when the browser first reports it ([NwApi.endpointFound]) and **pruned** when the browser
     * reports it removed ([NwApi.endpointLost], #1447 item 2), so a departed peer does not linger as a
     * ghost. Removal is best-effort (a binding with no removal signal never prunes), so this is a *hint*
     * for a lobby UI, never authoritative membership — that is [Seam.peers]' job.
     */
    public val visiblePeers: StateFlow<Set<NwEndpoint>> = _visiblePeers.asStateFlow()

    override fun capability(): TransportCapability =
        TransportCapability(roles = NW_ROLES, availability = api.availability())

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        // Derive from the caller so background work runs on the test dispatcher; independent Job
        // so seam close() cancels only this session's coroutines.
        val seamScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val seam = NwSeam(selfId, api, seamScope, random, policy, wovenPathGrace, capability())

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
            onLost = { endpoint -> _visiblePeers.update { it - endpoint } },
        )
        redial.start()

        runCatchingCancellable { api.startListening(serviceName, serviceType) }
            .onFailure { log.debug { "nw.listen failed serviceName=$serviceName selfId=${selfId.value}" } }
        runCatchingCancellable { api.startBrowsing(serviceType) }
            .onFailure { log.debug { "nw.browse failed serviceType=$serviceType selfId=${selfId.value}" } }

        // Await the first resolved remote so the returned seam is already connected. On ANY exit before
        // the seam is returned, the caller can never close() it — so we MUST close it here, or its scope
        // (a parentless SupervisorJob, NOT cancelled by the caller) leaks the RedialCoordinator loops,
        // which then dial every discovered endpoint at the backoff ceiling forever (#1513). Two exit paths:
        //  - timeout: the fabric never wove → close Unreachable and throw NwUnreachableException (NOT the
        //    raw TimeoutCancellationException, which a runCatchingCancellable caller would mistake for its
        //    own cancellation and rethrow);
        //  - ANY other throwable — crucially the CALLER cancelling while we await the first peer (the "wait
        //    for a friend" back-out: a user leaving the lobby, a withTimeout wrapper, scope teardown) —
        //    close the seam (under NonCancellable so the in-flight cancellation can't skip the close) and
        //    rethrow unchanged.
        try {
            withTimeout(weaveTimeout) {
                seam.peers.first { it.size > 1 }
            }
        } catch (_: TimeoutCancellationException) {
            log.info { "nw.loom.weave-timeout self=${selfId.value} serviceType=$serviceType after=$weaveTimeout → Unreachable" }
            withContext(NonCancellable) { runCatchingCancellable { seam.close(CloseReason.Unreachable) } }
            throw NwUnreachableException(
                "nw weave timed out: no peer reached for serviceType=$serviceType within $weaveTimeout",
            )
        } catch (e: Throwable) {
            log.info { "nw.loom.weave-aborted self=${selfId.value} serviceType=$serviceType reason=${e::class.simpleName} → closing seam so redial stops" }
            withContext(NonCancellable) { runCatchingCancellable { seam.close(CloseReason.Unreachable) } }
            throw e
        }
        log.debug { "nw.loom.wove self=${selfId.value} peers=${seam.peers.value.map { it.value }} state=${seam.state.value}" }
        return seam
    }

    public companion object {
        /**
         * The BASE roles a Network.framework fabric plays: it both finds peers ([TransportRole.Discovery], via
         * Bonjour advertise+browse) and carries frames ([TransportRole.Data]). The single source of these
         * roles for both [capability] (pre-connect static) and the [NwSeam] capability seed (live per-session).
         * The pre-connect [capability] carries exactly this base set (no path observed yet). Per-session, the
         * live path monitor now folds the observed Wi-Fi medium ([TransportRole.WifiLan] infrastructure vs
         * [TransportRole.WifiDirect] peer-to-peer AWDL) ON TOP of this base — recovered from the BSD interface
         * name by [classifyWifiInterface] (#1554), the follow-up to #1541's availability-only reactive capability.
         */
        internal val NW_ROLES: Set<TransportRole> = setOf(TransportRole.Discovery, TransportRole.Data)

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
 * [NwLoom.MAX_REDIAL_BACKOFF], with jitter from [jitterRandom]); once it settles the loop parks on the
 * flow and wakes only when the endpoint's peer drops (the seam re-forms to Weaving). A genuinely-new
 * `endpointFound` sighting starts a redialer at the initial backoff; a re-emit for an already-tracked
 * endpoint does NOT reset it. Every coroutine runs on the seam [scope], so [Seam.close] cancels them all.
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
 * each iteration snapshots what it needs under the lock and acts outside it. The backoff jitter draws
 * from [jitterRandom] — a DEDICATED [Random] seeded once at construction from the injected `random` —
 * NOT the seam's shared `random`: the seam uses its `random` for nonce generation on its own coroutines,
 * and Kotlin's `XorWowRandom` is not thread-safe, so sharing it across the seam's loops and every redial
 * coroutine would be a data race under a multi-threaded dispatcher. [jitterRandom] is touched only under
 * [lock], so the redial coroutines don't race each other on it either. Correct under a multi-threaded
 * dispatcher; no single-thread-confinement crutch.
 */
private class RedialCoordinator(
    private val api: NwApi,
    private val seam: NwSeam,
    private val scope: CoroutineScope,
    private val selfId: PeerId,
    random: Random,
    private val onDiscovered: (NwEndpoint) -> Unit,
    private val onLost: (NwEndpoint) -> Unit,
) {
    private val lock = reentrantLock()

    // Seeded once from the injected `random` (a single call at construction, before the seam's loops
    // begin consuming `random`, so no race here), then used only for redial jitter under [lock] — never
    // shared with the seam's nonce RNG. Deterministic under a seeded test `random`.
    private val jitterRandom: Random = Random(random.nextLong())

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
        // Prune the discovery roster when the browser reports an endpoint gone (#1447 item 2). This ONLY
        // touches the [onLost]-fed visiblePeers roster — it deliberately does NOT stop that endpoint's
        // redial loop: a real Bonjour removal is often transient interface churn (AWDL↔WiFi swaps), and
        // #1513's unbounded redial (keyed on [NwSeam.settledEndpoints], not on discovery) is what recovers
        // a flapping peer. Tearing the redialer on a removal would defeat that reconnection.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.endpointLost.collect { onEndpointLost(it) }
        }
    }

    private fun onEndpointFound(endpoint: NwEndpoint) {
        // Drop this loom's OWN endpoint. A device that advertises AND browses the same type is delivered
        // its own advertisement by real Bonjour/mDNS (and by FakeNwRadio, #1485). The self-filter keys on
        // the STABLE [NwEndpoint.id] — the peer's PeerId, published per-peer in the Bonjour TXT record
        // (Option A, #1502) — NOT on the human-readable serviceName. This matters under Rendezvous.New,
        // where EVERY peer advertises the same shared session name as its serviceName: a serviceName-keyed
        // filter could never recognise self there, so the loom dialled its own endpoint dozens of times
        // per session (caught only post-connect by the NwSeam guard) — the AWDL-only symptom of #1502.
        // For Rendezvous.Existing the id is still selfId (the loom advertises selfId as both name and TXT
        // id), so this stays correct there too. Backstop: if a browsed endpoint carries no TXT PeerId,
        // RealNwApi falls back to id = serviceName; under Rendezvous.New that is the shared name, so this
        // filter cannot fire and the NwSeam self-connection guard resolves+settles it post-connect (#1466).
        //
        // The filter is an OR of id AND serviceName against selfId, because the two can DIVERGE when the
        // advertiser and the loom don't share one selfId. On the JVM↔native bridge the loom defaults its
        // selfId while the dylib's RealNwApi defaults its OWN (a distinct UUID) — until the bridge threads
        // a single selfId across the ABI (#1539), self's own advertisement arrives as
        // (id = dylib-selfId, serviceName = loom-selfId). An id-only filter would miss it and reintroduce
        // the #1502 self-dial under Rendezvous.Existing (where serviceName == loom-selfId). The serviceName
        // clause is a safe backstop: a real peer never advertises OUR selfId as its serviceName, and under
        // Rendezvous.New serviceName is the shared session name (never a PeerId), so the clause is inert
        // there and the id clause does the real work.
        if (endpoint.id == selfId.value || endpoint.serviceName == selfId.value) {
            log.info { "nw.loom.self-skip endpoint=${endpoint.id} serviceName=${endpoint.serviceName} self=${selfId.value}" }
            return
        }
        onDiscovered(endpoint)
        val armed = lock.withLock {
            val existing = redialers[endpoint.id]
            // Reset backoff ONLY on a genuinely-NEW endpoint (a new [Redialer] starts at the initial
            // backoff by construction). A re-emit for an already-tracked endpoint must NOT reset it:
            // RealNwApi re-emits `endpointFound` on every `nw_browser` results-changed callback (AWDL↔WiFi
            // interface swaps, TXT-record churn, unrelated roster changes) — exactly during the flaky
            // periods a redial targets — so resetting on a re-emit would peg a present-but-unreachable
            // flapping peer at ~250ms forever instead of backing off to the ceiling. A reconnect (the peer
            // was connected then dropped) resets the backoff in [redialLoop] on the settled→un-settled edge.
            val r = existing ?: Redialer(endpoint).also { redialers[endpoint.id] = it }
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
        // INFO: the complement of nw.loom.self-skip — together they say, per sighting, whether the
        // pre-dial self-filter fired and on what operands. Only INFO+ reaches the on-device store.
        log.info {
            "nw.loom.discovered endpoint=${endpoint.id} serviceName=${endpoint.serviceName} " +
                "self=${selfId.value}${if (armed) " → redial armed" else " (already redialing)"}"
        }
    }

    private fun onEndpointLost(endpoint: NwEndpoint) {
        // Symmetric with the self-skip in [onEndpointFound] (id OR serviceName == selfId, #1502/#1539):
        // this loom's own endpoint was never added to the roster, so there is nothing to prune (and pruning
        // a set that never held it is a harmless no-op).
        if (endpoint.id == selfId.value || endpoint.serviceName == selfId.value) return
        onLost(endpoint)
        log.debug { "nw.loom.lost endpoint=${endpoint.id} self=${selfId.value} → pruned from visiblePeers" }
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
            // Snapshot the backoff, advance it (doubling to the ceiling), and draw the jitter — all under
            // the lock so [jitterRandom] is never touched concurrently by another redial coroutine.
            val delayMs = lock.withLock {
                val r = redialers[endpointId] ?: return
                val w = r.backoffMs
                r.backoffMs = (r.backoffMs * 2).coerceAtMost(NwLoom.MAX_REDIAL_BACKOFF.inWholeMilliseconds)
                w + jitterRandom.nextLong(0, (w / 4).coerceAtLeast(1))
            }
            // Wait the backoff, but wake the instant the endpoint settles so we stop dialing promptly.
            withTimeoutOrNull(delayMs.milliseconds) {
                seam.settledEndpoints.first { endpointId in it }
            }
        }
    }
}
