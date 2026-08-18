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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import us.tractat.kuilt.core.LoomDefaults
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.freshPeerId
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
 * Bonjour [serviceType], so the advertised *service name* does not gate who connects — but it IS the
 * dial target, which mDNS re-resolves at connect time, so it must name exactly one device: **both**
 * rendezvous arms advertise this peer's own [selfId] (ADR-005 / #2416). Under [Rendezvous.New] that
 * used to be the session name, shared by every peer, which made a dial armed for one peer able to land
 * on another — or on self.
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
 * ## Deferring the dial until identity resolves (#1709)
 * A browse `add` can arrive BEFORE the endpoint's TXT record does, so [NwEndpoint.id] is briefly the
 * [NwEndpoint.serviceName] backstop rather than the peer's `PeerId`. When an endpoint arrives with
 * `identityResolved = false` under the serviceName this loom itself advertises, the dial is
 * **deferred**: the endpoint might be self, and there is no way to tell yet. It is neither dialled nor
 * rostered into [visiblePeers] until identity settles one way or the other. The deferral is **bounded**
 * by [IDENTITY_GRACE] so a peer that publishes no TXT at all is still reached — see that constant.
 *
 * Since ADR-005 (#2416) the advertised name IS `selfId.value`, so the backstop id equals the resolved
 * TXT id and the pre-dial self-filter already fires on an unresolved *self* sighting. That makes this
 * deferral near-redundant on both arms; it is retained deliberately (its retirement is its own PR, so a
 * revert-check can isolate either half) and still covers a sighting whose id and serviceName diverge —
 * the JVM bridge, where loom and dylib default independent UUIDs (#2419).
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
 *   [NwUnreachableException] (default [DEFAULT_WEAVE_TIMEOUT]). Injectable (#1447 item 1) so a
 *   "wait for a friend" lobby can pass a generous value and hold the session open far longer than the
 *   default; deliberately NOT infinite by default. Tests inject a small value.
 * @param wovenPathGrace how long a path-lost (`ready → waiting`) connection is given to recover before
 *   the woven seam tears it as [CloseReason.Unreachable] (#1478); default [DEFAULT_WOVEN_PATH_GRACE]
 *   (10s), injectable for tests.
 */
public class NwLoom(
    private val api: NwApi,
    private val serviceType: String,
    public val selfId: PeerId = freshPeerId(),
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val random: Random = Random.Default,
    private val weaveTimeout: Duration = DEFAULT_WEAVE_TIMEOUT,
    private val wovenPathGrace: Duration = DEFAULT_WOVEN_PATH_GRACE,
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
        // NW_ROLES, not capability(): the seam takes ROLES only. Its availability may come from the path
        // monitor and nowhere else — `capability().availability` is a platform-support answer (#1712).
        val seam = NwSeam(selfId, api, seamScope, random, policy, wovenPathGrace, NW_ROLES)

        // Per-peer, NOT the session name (ADR-005 / #2416). Every peer sharing one instance name made
        // the dial target ambiguous: identity comes from the TXT record, but the dial goes to a NAME
        // that mDNS re-resolves at connect time, so a dial armed for one peer could land on another —
        // or on self. `Rendezvous.Existing` already advertised `selfId.value`; both arms now agree, and
        // the pre-TXT fallback id (= serviceName) equals the resolved TXT id, so exactly one redialer
        // is armed per peer.
        val serviceName = selfId.value
        log.debug { "nw.loom.weave self=${selfId.value} serviceType=$serviceType serviceName=$serviceName rendezvous=${rendezvous::class.simpleName}" }

        // Subscribe to discovery BEFORE advertising/browsing (subscribe-before-trigger: the API's flows
        // are hot with no replay). The coordinator's endpointFound collector is UNDISPATCHED so it is live
        // before the first emit; it drives redial with backoff (#1513) instead of a one-shot dial.
        val redial = RedialCoordinator(
            api = api,
            seam = seam,
            scope = seamScope,
            selfId = selfId,
            // The name THIS loom advertises — the discriminator for the #1709 identity deferral: an
            // endpoint under this name whose identity has not resolved could be our own advertisement.
            advertisedServiceName = serviceName,
            random = random,
            onDiscovered = { endpoint -> _visiblePeers.update { it + endpoint } },
            onLost = { endpoint -> _visiblePeers.update { it - endpoint } },
        )
        redial.start()

        // The listen — including the FIRST one — belongs to the supervisor (#2449). `startListening` returns
        // Unit and the OS reports the registration later on a callback, so a failure is invisible to any
        // `runCatchingCancellable` around the call; and a listen that throws synchronously must still start
        // the campaign at attempt 1 rather than leave a supervisor parked on a verdict that never comes.
        // One call site, one failure policy.
        ListenSupervisor(api, seamScope, serviceName, serviceType, selfId).startSeededWithInitialListen()
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
            discardUnreturnedSeam(seam)
            throw NwUnreachableException(
                "nw weave timed out: no peer reached for serviceType=$serviceType within $weaveTimeout",
            )
        } catch (e: Throwable) {
            log.info { "nw.loom.weave-aborted self=${selfId.value} serviceType=$serviceType reason=${e::class.simpleName} → closing seam so redial stops" }
            discardUnreturnedSeam(seam)
            throw e
        }
        log.debug { "nw.loom.wove self=${selfId.value} peers=${seam.peers.value.map { it.value }} state=${seam.state.value}" }
        return seam
    }

    /**
     * Close a seam [weave] wove but will never return, so its parentless [kotlinx.coroutines.SupervisorJob]
     * scope stops redialling every discovered endpoint forever (#1513). Shielded, because on both callers'
     * paths the throwable being handled may itself be this coroutine's cancellation and `Seam.close`
     * suspends.
     *
     * `try`/`catch (Throwable)` rather than `runCatchingCancellable` — the distinction this file documents at
     * `:41-48` and, until #1803, contradicted right here. Inside the shield this block's Job is parented to
     * [NonCancellable], so a `CancellationException` arriving here can only be one `close` minted itself (a
     * close-handshake `withTimeout` — non-conforming since #1826 put the "must not report failure as a
     * cancellation" obligation on `Seam.close` too, but a library cannot trust a consumer to honour it).
     * `runCatchingCancellable` rethrows exactly that case —
     * which would replace the caller's deliberate [NwUnreachableException] (or the original abort cause) with
     * a bare masquerading cancellation, silently cancelling the caller instead of failing it.
     */
    private suspend fun discardUnreturnedSeam(seam: Seam) {
        withContext(NonCancellable) {
            try {
                seam.close(CloseReason.Unreachable)
            } catch (failure: Throwable) {
                log.debug { "nw.loom.discard-close-failed self=${selfId.value} reason=${failure::class.simpleName}" }
            }
        }
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
        public val DEFAULT_WEAVE_TIMEOUT: Duration = LoomDefaults.WEAVE_TIMEOUT

        /**
         * How long a path-lost (`ready → waiting`) connection is given to recover before the woven seam
         * evicts the peer and tears the connection as [CloseReason.Unreachable] (#1478) — the public
         * name for the fabric's grace window, so a consumer (or an out-of-tree hardware harness) can
         * read the shipping value rather than mirroring the literal.
         *
         * The value itself lives on `NwSeam`, which is `internal`; this is the one exported alias.
         */
        public val DEFAULT_WOVEN_PATH_GRACE: Duration = NwSeam.DEFAULT_WOVEN_PATH_GRACE

        /** First redial backoff after a dial that has not (yet) connected (#1513). */
        internal val INITIAL_REDIAL_BACKOFF: Duration = 250.milliseconds

        /** Ceiling the redial backoff doubles up to — a gone endpoint's dials keep failing fast here (#1513). */
        internal val MAX_REDIAL_BACKOFF: Duration = 5.seconds

        /**
         * How long a possibly-self endpoint whose TXT identity has NOT resolved is held back before it is
         * dialled on the [NwEndpoint.serviceName] fallback anyway (#1709).
         *
         * **Why bounded at all.** `RealNwApi.onBrowseResult` cannot distinguish "TXT has not arrived yet"
         * from "this advertiser publishes no TXT" — both surface as `readPeerIdFromTxt() == null`. A hard
         * gate would therefore never dial a peer on a pre-#1673 build, trading a self-dial storm for a
         * liveness cliff. After the grace the loom dials the fallback id and the post-connect `NwSeam`
         * self-connection guard (which resolves the `PeerId` from the [NwHello] handshake) stays the
         * correctness backstop, exactly as it is today.
         *
         * **Why 750 ms.** Bonjour resolves a TXT record within tens of milliseconds on a warm mDNS cache
         * and a few hundred on a cold AWDL link, so 750 ms clears the observed resolution window with
         * room to spare while staying far below [DEFAULT_WEAVE_TIMEOUT] and below
         * [MAX_REDIAL_BACKOFF] (5 s) — a peer held back by the grace is delayed by less than one redial
         * backoff step, and a full mesh has that peer dialling US in the meantime regardless.
         *
         * **Residual, stated plainly.** The deferral is dropped early the moment this loom sees its OWN
         * advertisement resolve under the same serviceName, which is the common case and is what removes
         * the self-dial outright. When it is not dropped, the post-grace dial is a coin flip over the
         * endpoints collapsed onto that name and may still land on self — once, not repeatedly, since the
         * seam guard settles the endpoint and the redial loop parks.
         *
         * **What ADR-005 (#2416) changed.** That residual needed two peers to share one instance name.
         * Both rendezvous arms now advertise `selfId.value`, so no two peers collide on a name and the
         * unresolved backstop id is already this peer's own `PeerId` — the self-filter fires without TXT
         * and the coin flip has nothing to flip over. This grace therefore no longer guards the #1660
         * root-1 self-dial on either arm; what remains is the liveness bound above, plus the JVM bridge's
         * id/serviceName divergence (#2419). Retiring it is its own change.
         */
        internal val IDENTITY_GRACE: Duration = 750.milliseconds

        /** First back-off after the inbound listener reports [NwListenerState.Failed], before re-listening (#2449). */
        internal val INITIAL_LISTEN_BACKOFF: Duration = 500.milliseconds

        /** Ceiling the listen back-off doubles up to (#2449) — a path that can never bind stops burning attempts here. */
        internal val MAX_LISTEN_BACKOFF: Duration = 8.seconds

        /**
         * How many CONSECUTIVE listener failures a campaign absorbs before it gives up and parks on a
         * device-path change (#2449).
         *
         * The failure is **recoverable by re-creating the listener**, which is what makes retrying right at
         * all: Apple's own `com.apple.network:listener` log shows `Error advertising bonjour service: DNS
         * Error: DefunctConnection` (`dns(2)/-65569`) on app suspend, with the listener's TCP inboxes
         * starting fine across every interface (`en0`/`en1`/`awdl0`/`pdp_ip0`/`utun*`) milliseconds
         * earlier. A dead advertiser channel to mDNSResponder, not a bind conflict. The
         * 0.5 + 1 + 2 + 4 + 8 s ≈ 15.5 s of cover is sized for a resume settling its interface set.
         *
         * Bounded rather than unbounded because the same signal cannot distinguish a re-registration that
         * will succeed on the next attempt from one that will keep failing, and a peer must not spin on the
         * second forever. What makes the give-up safe is that it is not final: a [NwApi.pathState] change
         * re-arms a fresh campaign — and since suspend/resume churns the interface set, that is the signal
         * most likely to arrive precisely when a retry would now succeed.
         */
        internal const val MAX_LISTEN_ATTEMPTS: Int = 6

        /**
         * How long the listen campaign will sit on a NON-VERDICT listener state before treating the stall as
         * a failure and re-creating (#2449).
         *
         * `nw_listener_state_t` has five values; only `ready` and `failed` are verdicts. A listener parked in
         * `waiting` ("waiting for a usable network before being able to receive connections") is the expected
         * state of a phone resuming from suspend, and it can stay there indefinitely — so an unbounded wait
         * for a verdict is a silent park, the same family of bug as a `startListening` that throws.
         *
         * Generous rather than tight: this is a backstop against a stall, not an assertion about how fast a
         * listener should come up. A healthy listener resolves in milliseconds and never reaches it, and a
         * `waiting` listener that recovers on its own is observed the instant it does. It deliberately does
         * NOT apply to [NwListenerState.Unknown] — see `awaitFailure`.
         */
        internal val LISTEN_VERDICT_TIMEOUT: Duration = 10.seconds
    }
}

/**
 * Keeps this peer LISTENING for as long as the seam is open (#2449) — the accept-side twin of
 * [RedialCoordinator], which does the same job for the dial side.
 *
 * ## The gap it fills
 * [NwApi.startListening] is `suspend fun … : Unit`. Network.framework decides whether the registration
 * succeeded *after* it returns, on a GCD callback, so the `runCatchingCancellable` in [NwLoom.weave] can
 * only catch a synchronous throw — and there is none. A listener refused at start, or torn down by the OS
 * while the app was suspended, produced no observable signal at all: **there was no retry because there
 * was no signal.** [NwApi.listenerState] is that signal; this watches it.
 *
 * ## What the retry must DO: re-create, never restart
 * The observed failure is `Error advertising bonjour service: DNS Error: DefunctConnection`
 * (`dns(2)/-65569`), triggered by **app suspend** — a screen-lock mid-game. What goes defunct is the
 * Bonjour **advertiser's** channel to mDNSResponder, not a TCP bind: the listener's inboxes were starting
 * fine on every interface milliseconds earlier. So re-arming the same `nw_listener_t` would re-arm the
 * very object whose advertiser channel is dead, and would not recover. Recovery requires a **fresh
 * listener and a fresh advertise descriptor**.
 *
 * This retries by calling [NwApi.startListening], which is exactly that: `RealNwApi` builds a new
 * `nw_listener_t` and a new `nw_advertise_descriptor_create_bonjour_service` on every call and cancels the
 * superseded handle. That is a load-bearing property of the binding, flagged as such at the swap site —
 * a "reuse the existing listener" optimisation there would silently turn this whole mechanism into a
 * retry that cannot work, which is worse than no retry because it looks like a fix.
 *
 * Bounded because a signal cannot tell a transient re-registration failure from one that will keep
 * failing: back off ([NwLoom.INITIAL_LISTEN_BACKOFF] → double → [NwLoom.MAX_LISTEN_BACKOFF]) for at most
 * [NwLoom.MAX_LISTEN_ATTEMPTS] CONSECUTIVE failures, then stop rather than advertise forever.
 *
 * ## The path-change trigger
 * Stopping is not final: a [NwApi.pathState] change re-arms a fresh campaign, and also cuts a pending
 * back-off short. This is not a speculative extra — the interface churn IS the cause, so a path change is
 * the single event most likely to arrive exactly when a retry would now succeed. In the field capture
 * `pathState` fired dozens of times through the dead window, including a real cellular↔Wi-Fi flap 70 s
 * before the host's listener failed, and nothing was watching any of it. On a binding whose `pathState`
 * never updates (the never-updating default) the give-up is simply permanent, which is honest.
 *
 * ## Scope and logging
 * Runs on the seam [scope], so [Seam.close] cancels it. Every attempt logs at `error` with `attempt=` and
 * `retryIn=`, so a terminal give-up is distinguishable in a field capture from a first failure that
 * recovered — the distinction the original single unconditional line could not make.
 */
private class ListenSupervisor(
    private val api: NwApi,
    private val scope: CoroutineScope,
    private val serviceName: String,
    private val serviceType: String,
    private val selfId: PeerId,
) {
    /**
     * Perform the FIRST listen inline on the caller's coroutine (preserving `weave`'s ordering), then launch
     * the campaign seeded with its outcome.
     *
     * The seeding is the point. When the initial listen was a separate call site that merely logged its own
     * throw, a synchronous failure there left the supervisor started but unaware: it parked awaiting a
     * verdict that would never come, and the peer stayed inbound-unreachable for the seam's life with no
     * error logged — exactly the pre-#2449 outcome the campaign exists to prevent. One failure policy, one
     * place.
     */
    suspend fun startSeededWithInitialListen() {
        val initialFailure = listenOnce(phase = "initial")
        scope.launch { supervise(initialFailure) }
    }

    private suspend fun supervise(initialFailure: NwListenerState.Failed?) {
        var consecutiveFailures = 0
        var backoff = NwLoom.INITIAL_LISTEN_BACKOFF
        // The failure this round is handling. Normally observed on [NwApi.listenerState], but a listen that
        // throws SYNCHRONOUSLY carries its own — including the very first one. Without that second source
        // the campaign wedges exactly where it must not: a throwing `startListening` publishes no verdict,
        // so awaiting one parks the supervisor forever and silently restores the pre-#2449 behaviour.
        var pendingFailure: NwListenerState.Failed? = initialFailure
        while (true) {
            val failure = pendingFailure ?: awaitFailure {
                consecutiveFailures = 0
                backoff = NwLoom.INITIAL_LISTEN_BACKOFF
            }
            pendingFailure = null
            consecutiveFailures += 1
            if (consecutiveFailures >= NwLoom.MAX_LISTEN_ATTEMPTS) {
                log.error {
                    "nw.listen.gave-up self=${selfId.value} serviceName=$serviceName " +
                        "attempt=$consecutiveFailures/${NwLoom.MAX_LISTEN_ATTEMPTS} " +
                        "nw_error domain=${nwErrorDomainName(failure.domain)}(${failure.domain}) code=${failure.code} " +
                        "— this peer is UNREACHABLE INBOUND (existing links keep working); " +
                        "no further listen until the device network path changes"
                }
                awaitPathChange()
                log.error {
                    "nw.listen.path-changed self=${selfId.value} serviceName=$serviceName " +
                        "path=${api.pathState.value} → re-arming the listen campaign that had given up"
                }
                // Reset the ATTEMPT count but deliberately NOT the back-off. A campaign that exhausted its
                // attempts re-arms at whatever interval it had reached, so sustained path churn cannot use
                // the give-up branch as a fast path back to a 0.5 s cadence.
                consecutiveFailures = 0
            } else {
                log.error {
                    "nw.listen.retry self=${selfId.value} serviceName=$serviceName " +
                        "attempt=$consecutiveFailures/${NwLoom.MAX_LISTEN_ATTEMPTS} " +
                        "nw_error domain=${nwErrorDomainName(failure.domain)}(${failure.domain}) code=${failure.code} " +
                        "retryIn=$backoff"
                }
            }
            waitBeforeRelisten(backoff)
            backoff = (backoff * 2).coerceAtMost(NwLoom.MAX_LISTEN_BACKOFF)
            pendingFailure = listenOnce(phase = "retry")
        }
    }

    /**
     * Wait [backoff] before the next re-listen, waking early on a device-path change — but never sooner than
     * a floor of [NwLoom.INITIAL_LISTEN_BACKOFF].
     *
     * The floor is the whole point, and it is not a nicety. Every re-listen is a fresh `nw_listener_create`
     * plus a fresh Bonjour registration plus a cancel of the superseded handle, aimed at mDNSResponder —
     * **whose unhealthy channel is the very thing being recovered from**. Letting a path change collapse the
     * wait to zero would make the effective retry rate the `pathState` update rate, and iOS emits those in
     * bursts during exactly the resume this targets: the attempt bound would buy nothing and the fix would
     * become a re-registration storm pointed at the sick component. So a path change may shorten the wait,
     * never remove it. This is the single place any re-listen waits, so the floor cannot be bypassed by
     * adding a branch above.
     */
    private suspend fun waitBeforeRelisten(backoff: Duration) {
        val floor = minOf(NwLoom.INITIAL_LISTEN_BACKOFF, backoff)
        delay(floor)
        withTimeoutOrNull(backoff - floor) { awaitPathChange() }
    }

    /**
     * Listen once, returning a synthesized [NwListenerState.Failed] when the call threw synchronously and
     * `null` when it handed off normally (the usual case — Network.framework reports asynchronously).
     *
     * The synthesized failure carries [NW_ERROR_DOMAIN_INVALID]/0 rather than an invented domain, matching
     * how `RealNwApi` reports a FAILED transition it could not decode.
     *
     * `try`/`catch` with an explicit [ensureActive], NOT `runCatchingCancellable`. [NwApi] is a public
     * interface, so `startListening` may be consumer-authored, and a `withTimeout` inside one mints a
     * `TimeoutCancellationException` **at its caller** without cancelling that caller.
     * `runCatchingCancellable` rethrows both cancellations alike, so that minted one would escape into
     * `scope.launch { supervise(…) }` under a `SupervisorJob` — where the child is **cancelled rather than
     * failed**: no handler runs, no stack trace is logged, and the entire campaign disappears silently. The
     * whole campaign is work that follows this call, which is CLAUDE.md's trigger for the explicit form;
     * none of its documented elisions cover `startListening`. [ensureActive] is what tells the two apart at
     * runtime — it throws only when THIS job is genuinely cancelled.
     */
    private suspend fun listenOnce(phase: String): NwListenerState.Failed? =
        try {
            api.startListening(serviceName, serviceType)
            null
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            log.error { "nw.listen.threw self=${selfId.value} serviceName=$serviceName phase=$phase: $failure" }
            NwListenerState.Failed(NW_ERROR_DOMAIN_INVALID, code = 0)
        }

    /**
     * Suspend until the listener reports [NwListenerState.Failed], calling [onReady] whenever it comes up
     * [NwListenerState.Ready] first — a listener that genuinely came up ends the *consecutive* failure run,
     * so a session that loses its listener once an hour never walks into the give-up.
     *
     * ## Why the verdict wait is BOUNDED
     * `nw_listener_state_t` has five values and only two of them are verdicts. A listener can sit in
     * `waiting` ("waiting for a usable network before being able to receive connections") indefinitely —
     * which is precisely where a phone resuming from suspend with a churning interface set is expected to
     * be. An unbounded `first { Ready || Failed }` would park the campaign there forever, emitting no
     * `attempt=` and no `gave-up`: invisible in a field capture, and the same silent-park family as a
     * throwing `startListening`. So a stall past [NwLoom.LISTEN_VERDICT_TIMEOUT] is converted into a failure
     * that advances the campaign, carrying the waiting state's own decoded reason when it has one. No
     * unmodelled state can park this loop.
     *
     * The ONE deliberate exception is [NwListenerState.Unknown]: that is the never-updating default of a
     * binding which wires no listener signal at all. It has no verdict to give and never will, so treating
     * its silence as a failure would turn an inert default into a perpetual re-listen loop on every such
     * binding. That case parks — permanently, and by design, since it is exactly the pre-#2449 behaviour.
     *
     * Reading [NwApi.listenerState] as a [kotlinx.coroutines.flow.StateFlow] means a [NwListenerState.Failed]
     * can never be missed, only observed late. It can be *conflated over*: a `Ready` immediately superseded
     * by a `Failed` may never be seen, which costs at most one un-reset counter — the conservative direction.
     */
    private suspend fun awaitFailure(onReady: () -> Unit): NwListenerState.Failed {
        while (true) {
            val settled = withTimeoutOrNull(NwLoom.LISTEN_VERDICT_TIMEOUT) {
                api.listenerState.first { it is NwListenerState.Ready || it is NwListenerState.Failed }
            }
            if (settled is NwListenerState.Failed) return settled
            if (settled is NwListenerState.Ready) {
                onReady()
                log.info { "nw.listen.ready self=${selfId.value} serviceName=$serviceName — listener up; back-off reset" }
                // Park until it leaves Ready, so the next failure is observed rather than spun on. Unbounded
                // deliberately: Ready is the healthy steady state, not a stall.
                api.listenerState.first { it !is NwListenerState.Ready }
                continue
            }
            when (val stalled = api.listenerState.value) {
                is NwListenerState.Unknown -> {
                    log.debug {
                        "nw.listen.no-signal self=${selfId.value} serviceName=$serviceName — this binding " +
                            "reports no listener state; parking (no signal, so nothing to retry on)"
                    }
                    api.listenerState.first { it !is NwListenerState.Unknown }
                }
                is NwListenerState.Waiting -> {
                    log.error {
                        "nw.listen.stalled self=${selfId.value} serviceName=$serviceName state=waiting " +
                            "nw_error domain=${nwErrorDomainName(stalled.domain)}(${stalled.domain}) code=${stalled.code} " +
                            "after=${NwLoom.LISTEN_VERDICT_TIMEOUT} → treating as a failure and re-creating the listener"
                    }
                    return NwListenerState.Failed(stalled.domain, stalled.code)
                }
                else -> {
                    log.error {
                        "nw.listen.stalled self=${selfId.value} serviceName=$serviceName state=$stalled " +
                            "after=${NwLoom.LISTEN_VERDICT_TIMEOUT} → no verdict; treating as a failure"
                    }
                    return NwListenerState.Failed(NW_ERROR_DOMAIN_INVALID, code = 0)
                }
            }
        }
    }

    /**
     * Suspend until the device's network path CHANGES from whatever it is right now. On a binding whose
     * [NwApi.pathState] is the never-updating default this parks forever — deliberately: no path signal
     * means no re-arm trigger, which is exactly the pre-#2449 behaviour for that binding.
     */
    private suspend fun awaitPathChange() {
        val observedAt = api.pathState.value
        api.pathState.first { it != observedAt }
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
 * ## Identity deferral (#1709)
 * A sighting whose identity has not resolved ([NwEndpoint.identityResolved] `false`) and whose
 * [NwEndpoint.serviceName] is the name THIS loom advertises ([advertisedServiceName]) *could be this
 * loom's own endpoint*, since the fallback id is then the same string for self and the sighting. Such a
 * sighting arms no redialer and is not rostered; instead a [deferrals] entry holds it for
 * [NwLoom.IDENTITY_GRACE]. Two things end the wait:
 *  - **our own advertisement resolves under that name** — a resolved self sighting proves this loom
 *    occupies the name, so the pending fallback dial is dropped (it would be a coin flip that may hit
 *    self, which is the whole bug). A later unresolved sighting under the name re-arms a fresh
 *    deferral, so a TXT-less peer arriving afterwards is still reached;
 *  - **the grace expires** — the endpoint is armed on its fallback id, deliberately (see
 *    [NwLoom.IDENTITY_GRACE]: an advertiser that publishes no TXT is indistinguishable from one whose
 *    TXT is merely late, and must not be starved).
 *
 * ## Thread-safety
 * The [redialers] and [deferrals] maps and each entry's mutable `backoffMs`/`job` are read-modify-written
 * only under [lock] (atomicfu). No suspend call ([NwApi.connect], [delay], flow collection) runs under the lock:
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
    private val advertisedServiceName: String,
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

    /**
     * Bonjour serviceName → the pending grace timer for a possibly-self endpoint under that name whose
     * identity has not resolved (#1709). Guarded by [lock]. Keyed on the serviceName rather than the id
     * because that is the *collision set* the ambiguity lives in: an unresolved endpoint's id IS its
     * serviceName (the `readPeerIdFromTxt() ?: name` fallback), so every unresolved sighting under one
     * name shares a single entry, and the resolved self sighting that drops it is matched by name too.
     *
     * Since ADR-005 (#2416) an unresolved sighting under [advertisedServiceName] carries `id ==
     * selfId.value` and is caught by the self-filter first, so this map is normally empty; it still
     * holds the case where a sighting's id and serviceName diverge (the JVM bridge, #2419).
     */
    private val deferrals = mutableMapOf<String, Job>()

    /**
     * Bonjour serviceName → the FIRST resolved peer id seen advertising under it, for
     * [observeNameOwner]. Seeded with this loom's own advertisement, so a peer colliding with *our*
     * name is caught as readily as two remote peers colliding with each other. Guarded by [lock];
     * bounded by the number of distinct names seen, the same bound [deferrals] already carries.
     *
     * The incumbent is never overwritten: a second owner must keep comparing against the *same*
     * reference for [reportedNameCollisions] to dedup it.
     */
    private val nameOwners = mutableMapOf(advertisedServiceName to selfId.value)

    /**
     * Collisions already reported, keyed by name plus the id pair in a canonical order, so the warning
     * fires **once per colliding pair** rather than on every browse re-emit. Guarded by [lock].
     */
    private val reportedNameCollisions = mutableSetOf<String>()

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
        // BEFORE any filtering: a sighting we are about to drop as self is still evidence about who holds
        // which name, and under a shared name the colliding peer is exactly the one the self-filter drops.
        observeNameOwner(endpoint)
        // Drop this loom's OWN endpoint. A device that advertises AND browses the same type is delivered
        // its own advertisement by real Bonjour/mDNS (and by FakeNwRadio, #1485). The self-filter keys on
        // the STABLE [NwEndpoint.id] — the peer's PeerId, published per-peer in the Bonjour TXT record
        // (Option A, #1502) — rather than on the serviceName alone. That was load-bearing when
        // Rendezvous.New made EVERY peer advertise one shared session name: a serviceName-keyed filter
        // could never recognise self there, so the loom dialled its own endpoint dozens of times per
        // session (caught only post-connect by the NwSeam guard) — the AWDL-only symptom of #1502.
        //
        // Since ADR-005 (#2416) BOTH rendezvous arms advertise selfId.value, so the two clauses now agree
        // on the P2P path and the pre-TXT window stops being dangerous: when a browsed endpoint carries no
        // TXT PeerId, RealNwApi falls back to id = serviceName — which is that peer's own PeerId — so this
        // filter fires on an unresolved SELF sighting too, with no TXT record and no identity grace. The
        // NwSeam self-connection guard (#1466) remains the post-connect backstop.
        //
        // The filter stays an OR of id AND serviceName against selfId, because the two can still DIVERGE
        // when the advertiser and the loom don't share one selfId. On the JVM↔native bridge the loom
        // defaults its selfId while the dylib's RealNwApi defaults its OWN (a distinct UUID) — until the
        // bridge threads a single selfId across the ABI (#2419), self's own advertisement arrives as
        // (id = dylib-selfId, serviceName = loom-selfId). An id-only filter would miss it and reintroduce
        // the #1502 self-dial — on BOTH arms now, since New advertises serviceName = loom-selfId too, which
        // is precisely why ADR-005 also closes the bridge's New-path self-dial. The serviceName clause is a
        // safe backstop: a real peer never advertises OUR selfId as its serviceName.
        if (endpoint.id == selfId.value || endpoint.serviceName == selfId.value) {
            // A RESOLVED self sighting proves our OWN advertisement occupies this serviceName. Any dial
            // still pending on that name's fallback id is therefore a coin flip that may land on self —
            // the #1709 bug itself — so drop it. Non-sticky on purpose: a later unresolved sighting under
            // the name re-arms a fresh deferral, so a genuinely TXT-less peer is not starved.
            if (endpoint.identityResolved) dropDeferralFor(endpoint.serviceName)
            log.info { "nw.loom.self-skip endpoint=${endpoint.id} serviceName=${endpoint.serviceName} self=${selfId.value}" }
            return
        }
        // #1709: identity has not resolved AND the endpoint is advertised under the name THIS loom
        // advertises, so this could be our own endpoint with its TXT record still in flight. Dialling now
        // is what reintroduced the #1660 root-1 self-dial for as long as TXT took to resolve. Hold it,
        // bounded, instead. Since ADR-005 (#2416) `advertisedServiceName` is selfId.value on BOTH arms, so
        // this cannot fire for a REMOTE peer on either — a match means the self-filter above already
        // returned — and it is reached only where id and serviceName diverge (the JVM bridge, #2419).
        if (!endpoint.identityResolved && endpoint.serviceName == advertisedServiceName) {
            deferUntilIdentityResolves(endpoint)
            return
        }
        arm(endpoint)
    }

    /**
     * Warn when **two advertisers hold one Bonjour instance name** — the root condition of #2416, named
     * at the moment it becomes observable and BEFORE any dial to the contested name.
     *
     * A name is the *dial target*, and mDNS re-resolves it at connect time; an id is the *identity*, read
     * per-peer from the TXT record. When one name has two owners those two facts come apart, and a dial
     * armed FOR one peer can land on another — including the local device. Everything downstream (the
     * pre-dial self-filter, the #1709 deferral, `NwSeam`'s settle guard and #2417's settle rule) is
     * recovery from this condition; nothing *stated* it, which is why the 2026-08-15 hardware diagnosis
     * had to infer it from a `… (2)` rename that landed 6 s after the fatal dial.
     *
     * ## Why this is general, and not "is someone using MY name"
     * The narrow form — compare against [advertisedServiceName] — is **dead code** now that ADR-005 has
     * landed: both rendezvous arms advertise `selfId.value`, so a match on our own name always returns at
     * the self-filter first. It would also miss the mixed-version fleet that is what actually remains,
     * where two peers on an older build collide with each other on a shared session name while this peer,
     * already per-peer named, is not party to it. Tracking one owner per name catches every pair, whether
     * or not we are in it — [nameOwners] is seeded with our own advertisement precisely so that a peer
     * colliding with *us* is one more case of the same rule rather than a special one.
     *
     * ## Only resolved sightings claim a name
     * An unresolved sighting's id IS its serviceName (the `readPeerIdFromTxt() ?: name` fallback), so
     * recording it would compare a name against itself and manufacture a collision on the pre-TXT window
     * #1709 already handles. Ownership is claimed only by a real per-peer id.
     *
     * ## The one suppression, and why it is sound rather than a heuristic
     * A sighting whose serviceName is literally `selfId.value` is skipped. That is not a guess: it is the
     * premise the production self-filter above already rests on — *"a real peer never advertises OUR
     * selfId as its serviceName"* — and the only thing that produces that shape is the JVM bridge, where
     * loom and dylib each default an independent UUID so our own advertisement returns as
     * `(id = dylib-selfId, serviceName = loom-selfId)` and would report itself as a rival owner (#2419).
     * It costs nothing this line exists for: the #2416 collision that remains is two older-build peers on
     * a *shared session name*, which is not `selfId.value`, so it still fires; and a collision between two
     * remote peers never matches.
     *
     * Diagnostic only — it changes no decision and the endpoint is filtered/deferred/armed exactly as
     * before. WARN, once per (name, id-pair) via [reportedNameCollisions]: `RealNwApi` re-emits
     * `endpointFound` on every browse results-changed callback, so an unguarded line would flood.
     */
    private fun observeNameOwner(endpoint: NwEndpoint) {
        if (!endpoint.identityResolved || endpoint.serviceName == selfId.value) return
        val incumbent = lock.withLock {
            val held = nameOwners.getOrPut(endpoint.serviceName) { endpoint.id }
            // Same owner re-sighted, or the pair is already reported → nothing to say.
            if (held == endpoint.id) return
            val pair = listOf(held, endpoint.id).sorted()
            if (!reportedNameCollisions.add("${endpoint.serviceName}|${pair[0]}|${pair[1]}")) return
            held
        }
        log.warn {
            "nw.loom.name-collision serviceName=${endpoint.serviceName} self=${selfId.value} " +
                "owners=[$incumbent, ${endpoint.id}]${if (incumbent == selfId.value) " (one of them is US)" else ""} " +
                "— two advertisers hold one Bonjour name, so a dial to it may resolve to EITHER device " +
                "(#2416); ADR-005 removes the collision for this peer's own advertisement"
        }
    }

    /** Roster the endpoint and start (or leave running) its redial campaign. */
    private fun arm(endpoint: NwEndpoint) {
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

    /**
     * Hold a possibly-self, identity-unresolved [endpoint] for [NwLoom.IDENTITY_GRACE], then [arm] it on
     * its fallback id anyway (#1709). One timer per serviceName; a re-emit while one is pending, or while
     * that name's fallback id is already being redialled, is a no-op.
     *
     * The timer runs on the seam [scope], so it is driven by the caller's dispatcher (virtual time under
     * `runTest`) and is cancelled with the seam. `delay` is the first statement, so the body never touches
     * [lock] inline from under the `launch` site's own lock acquisition.
     */
    private fun deferUntilIdentityResolves(endpoint: NwEndpoint) {
        val deferred = lock.withLock {
            if (deferrals[endpoint.serviceName]?.isActive == true) return@withLock false
            if (redialers[endpoint.id]?.job?.isActive == true) return@withLock false
            deferrals[endpoint.serviceName] = scope.launch {
                delay(NwLoom.IDENTITY_GRACE)
                lock.withLock { deferrals.remove(endpoint.serviceName) }
                log.info {
                    "nw.loom.identity-grace-expired endpoint=${endpoint.id} serviceName=${endpoint.serviceName} " +
                        "self=${selfId.value} → dialling on the unresolved fallback id (advertiser may publish no TXT)"
                }
                arm(endpoint)
            }
            true
        }
        // INFO like its self-skip/discovered siblings: on-device telemetry keeps INFO+, and this is the
        // third possible verdict per sighting — deferred, alongside filtered and armed.
        if (deferred) {
            log.info {
                "nw.loom.identity-deferred endpoint=${endpoint.id} serviceName=${endpoint.serviceName} " +
                    "self=${selfId.value} — TXT unresolved under our own advertised name, could be self"
            }
        }
    }

    /** Drop a pending identity deferral for [serviceName] — its fallback dial is no longer safe to make. */
    private fun dropDeferralFor(serviceName: String) {
        val pending = lock.withLock { deferrals.remove(serviceName) } ?: return
        pending.cancel()
        log.info {
            "nw.loom.identity-deferral-dropped serviceName=$serviceName self=${selfId.value} — " +
                "our own advertisement resolved under this name, so its fallback id may be us"
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
            //
            // Both edges are logged at INFO because parking is TERMINAL-LOOKING and otherwise SILENT: a
            // redialer that parks on a wrongly-settled endpoint (#2416/#2417) never dials again for the
            // seam's lifetime and emits nothing at all, so a wedged device is indistinguishable from an
            // idle one in a hardware capture. `nw.seam.settled` carries the provenance of each entry —
            // read the two together to see WHICH endpoint parked this redialer and WHY it settled.
            if (endpointId in seam.settledEndpoints.value) {
                log.info {
                    "nw.loom.redial-parked endpoint=$endpointId self=${selfId.value} " +
                        "settled=${seam.settledEndpoints.value} — no further dial until it un-settles"
                }
                seam.settledEndpoints.first { endpointId !in it }
                log.info { "nw.loom.redial-resumed endpoint=$endpointId self=${selfId.value} → un-settled, redialing from the initial backoff" }
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
