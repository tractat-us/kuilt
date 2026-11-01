package us.tractat.kuilt.nearby

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerIdentityRegistry
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.freshPeerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger("us.tractat.kuilt.nearby.NearbyLoom")

/**
 * [Loom] implementation backed by Google Nearby Connections.
 *
 * All logic is GMS-free and lives in `commonMain`. The real binding that imports
 * `play-services-nearby` is supplied by `androidMain` at construction time.
 *
 * ## Concurrency and virtual-time correctness
 * Background coroutines are launched into a scope derived from the **caller's**
 * coroutine context at `open()`/`join()` time:
 * `CoroutineScope(currentCoroutineContext() + SupervisorJob())`.
 * This means background work inherits the test dispatcher (and therefore
 * `runTest`'s virtual clock) when tests call these methods. The scope is
 * stored and cancelled in [NearbySeam.close] so no coroutines leak between
 * tests. There is no long-lived process-global scope.
 *
 * ## Single-loom symmetric topology
 * One [NearbyLoom] handles both the advertiser role ([open]) and the discoverer
 * role ([join]) through the same [NearbyApi]. This matches the
 * [us.tractat.kuilt.core.InMemoryLoom] pattern and lets the conformance suite run
 * a "one loom, one host, one joiner" scenario.
 *
 * ## The roster belongs to the WEAVE, not to the loom (#1878)
 * Each [weave] mints its own peer-set [MutableStateFlow], seeded with that weave's
 * [us.tractat.kuilt.core.freshPeerId] and thereafter written only by that weave's own handshake
 * and departures. A single loom-wide flow — what this loom used to keep — is never pruned of a
 * finished weave's ids: [NearbySeam.close] must not write it (that write was the #1850 cross-peer
 * edit) and `disconnectLoop`, the only eviction, is cancelled by the very tear that would need it
 * to run. So a closed weave's id lingered forever and seeded the *next* weave, whose fresh seam
 * reported a roster containing a peer it had never met and latched
 * [us.tractat.kuilt.core.SeamState.Woven] with zero connections.
 *
 * Both ends of one session still converge, because each learns the other from the handshake it
 * already completed ([ConnectedLink.remotePeerId]) rather than by reading a flow the counterparty
 * wrote. That was always the honest channel — the loom-wide flow was a single-process convenience
 * of the fake harness, and on real hardware the two peers are on different devices with different
 * looms and have never shared one.
 *
 * ## No loom-level `selfId` — identity is minted per weave
 * Because one loom weaves *both* ends of a session (above), a loom-level identity would
 * hand the host seam and the joiner seam the **same** [PeerId] — collapsing the roster to
 * one entry, so `Weaving` would never reach `Woven`, and making every `sendTo` between them
 * fail its own `peer != selfId` precondition. Each weave therefore mints its own
 * [us.tractat.kuilt.core.freshPeerId], exactly as
 * [us.tractat.kuilt.core.InMemoryLoom] does. That is why the uniform-`Loom`-construction
 * convention's `selfId` knob is deliberately **absent** here rather than defaulted (#1430);
 * `NearbyLoomKnobsTest` pins the distinctness the omission rests on.
 *
 * ## A host accepts for exactly as long as it advertises (#2811)
 * One coroutine owns both: it takes the first joiner whose handshake succeeds, and advertising stops
 * when it ends, by a link, by [NearbySeam.close] or by the radio's event stream failing. Nothing else
 * ends it. In particular time does not: [handshakeTimeout] starts when a joiner initiates, so a host
 * nobody has come to is `Weaving` and listening indefinitely, and a failed attempt costs that attempt
 * alone. The failed endpoint is disconnected, advertising carries on untouched, and the next joiner is
 * taken as if the first had never come.
 *
 * It admits **one** joiner, and advertising stops once it has one, because [NearbySeam] relays nothing
 * between two joiners and the conformance harness claims mesh delivery only vacuously, over one link.
 * Accepting a second is a different feature with a different capability answer. A joiner arriving
 * mid-handshake is not queued: it is ignored, and fails on its own clock.
 *
 * ## A `join` waits for this loom's host only while that host is mid-handshake
 * One loom can play both ends of a session, and then `join` returning means the host's roster is
 * populated as well. So `join` waits for any handshake this loom's host has actually started
 * ([LocalHandshakes]), which its own bound limits. It does not wait for a host that has no handshake
 * in flight: such a host owes this `join` nothing, and since acceptance is long-lived, a wait for "the
 * host's first link" would last until somebody happened to join it. That was the deferred this
 * replaced, which also never completed if the host's wait ended any other way, so hosting a room,
 * leaving it and joining somebody else's left the `join` waiting forever.
 *
 * @param api               [NearbyApi] to use (real GMS or fake for tests).
 * @param serviceId         Nearby Connections service ID. Must match on both devices.
 * @param policy            Delivery policy for every woven seam's inbound buffer.
 * @param handshakeTimeout  Ceiling on ONE connection's handshake, forwarded to
 *                          [ConnectStateMachine]. **Not** a weave timeout — endpoint
 *                          discovery sits outside it (see [DEFAULT_HANDSHAKE_TIMEOUT]).
 * @param maxChunkPayload   Per-chunk payload cap forwarded to [ChunkCodec].
 */
public class NearbyLoom(
    private val api: NearbyApi,
    private val serviceId: String = DEFAULT_SERVICE_ID,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
    private val maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
) : Loom {

    // No loom-level peer set: each weave owns its own (#1878) — see the class KDoc.

    private val handshakes = LocalHandshakes()

    override fun capability(): TransportCapability =
        TransportCapability(roles = NEARBY_ROLES, availability = api.availability())

    /**
     * A cancellation leaves here only if it is the caller's own, as [Loom.weave] requires.
     * [NearbyApi] is consumer-authored and the GMS binding awaits Play services tasks, either of which
     * can throw one without this job being cancelled, and [currentCoroutineContext]'s `ensureActive` is
     * what tells them apart: type cannot.
     */
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        try {
            when (rendezvous) {
                is Rendezvous.New -> openSession(rendezvous.pattern)
                is Rendezvous.Existing -> joinSession(rendezvous.tag)
            }
        } catch (minted: CancellationException) {
            currentCoroutineContext().ensureActive()
            val session = when (rendezvous) {
                is Rendezvous.New -> rendezvous.pattern.sessionName
                is Rendezvous.Existing -> rendezvous.tag.sessionName
            }
            throw ConnectionFailedException(session, "the radio reported a cancellation: $minted")
        }

    /**
     * Start advertising and return a [Seam] immediately.
     *
     * A background coroutine accepts until a joiner's handshake succeeds, which puts that joiner on
     * the host seam; see the class KDoc. It subscribes before advertising starts, so there is no gap
     * in which a joiner could initiate with nobody listening.
     *
     * The background scope is derived from the caller's coroutine context so it
     * inherits the test dispatcher and is cleaned up when the seam is closed.
     */
    private suspend fun openSession(config: Pattern): Seam {
        val peerId = freshPeerId()
        val endpointPeers = mutableMapOf<String, PeerId>()
        // The weave's peer-identity authority (#1821), keyed by Nearby endpoint ID and shared with
        // the handshake that admits an id and the seam that evicts one, so both answer from the
        // same bindings. `endpointPeers` remains the reassembly/send index and is written only
        // where the registry has already said yes.
        val registry = PeerIdentityRegistry<String>(peerId)
        // Single mutex shared with the seam — all endpointPeers access on both sides
        // serialises on this one lock, eliminating the two-mutex race.
        val endpointPeersMutex = Mutex()
        // This weave's roster (#1878), seeded with its own id so the seam starts at { selfId } and
        // Weaving no matter what earlier weaves on this loom did. Seeding BEFORE construction (the
        // loom used to add the id after) also removes a transient in which the seam existed while
        // its own roster did not yet contain it.
        val weavePeers = MutableStateFlow(setOf(peerId))
        // Derive from caller so background work runs on the test dispatcher.
        val seamScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val advertiserRetired = Job()

        val seam = NearbySeam(
            selfId = peerId,
            endpointPeers = endpointPeers,
            endpointPeersMutex = endpointPeersMutex,
            registry = registry,
            api = api,
            weavePeers = weavePeers,
            scope = seamScope,
            maxChunkPayload = maxChunkPayload,
            msgIdCounter = MsgIdCounter(),
            policy = policy,
            advertiserRetired = advertiserRetired,
        )

        // UNDISPATCHED so the host's handshake collectors subscribe synchronously during open(),
        // before advertising starts and before any joiner's requestConnection emits events.
        seamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val lost = advertiseUntilLinked(peerId, registry, seam, advertiserRetired)
            // Outside the above, because close() waits for the advertiser that has only now retired.
            if (lost != null) seam.close(CloseReason.Error(lost))
        }

        try {
            api.startAdvertising(config.sessionName, serviceId)
        } catch (failure: Throwable) {
            discardUnreturnedSeam(seam)
            throw failure
        }
        return seam
    }

    /**
     * The lifetime of one host's advertising: accept until linked, then stop, on every exit. Returns
     * the failure if the radio's event stream is what ended it.
     */
    private suspend fun CoroutineScope.advertiseUntilLinked(
        peerId: PeerId,
        registry: PeerIdentityRegistry<String>,
        seam: NearbySeam,
        advertiserRetired: CompletableJob,
    ): NearbyEventStreamLostException? =
        try {
            acceptUntilLinked(peerId, registry, seam)
            null
        } catch (lost: NearbyEventStreamLostException) {
            log.error(lost) { "nearby.host.accept-lost self=${peerId.value} — tearing the seam" }
            lost
        } finally {
            withContext(NonCancellable) {
                try {
                    api.stopAdvertising()
                } catch (failure: Throwable) {
                    log.debug { "nearby.host.stop-advertising-failed self=${peerId.value} failure=$failure" }
                }
            }
            advertiserRetired.complete()
        }

    /**
     * One handshake after another until one produces a link. Each pass waits, unbounded, for a joiner,
     * so this cannot spin: every failure below needs a joiner to have turned up first.
     *
     * The `catch` is on the attempt, and it is why a failed attempt costs nothing else. `ensureActive`
     * first, so that this coroutine's real cancellation ends the loop while a cancellation a callee
     * minted costs one attempt like anything else (`AGENTS.md`, exception discipline).
     * [NearbyEventStreamLostException] is the one failure that is not one attempt's, and it passes.
     */
    private suspend fun CoroutineScope.acceptUntilLinked(
        peerId: PeerId,
        registry: PeerIdentityRegistry<String>,
        seam: NearbySeam,
    ) {
        while (true) {
            val attempt = handshakes.incomingAttempt()
            try {
                val link = ConnectStateMachine(
                    selfId = peerId,
                    api = api,
                    endpointId = null,
                    serviceId = serviceId,
                    handshakeTimeout = handshakeTimeout,
                    registry = registry,
                    mayClaim = attempt::tryClaim,
                ).run(this) {} // already advertising, so no kickoff
                admitOrRelease(seam, link)
                return
            } catch (lost: NearbyEventStreamLostException) {
                throw lost
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                log.info { "nearby.host.attempt-failed self=${peerId.value} failure=$failure — still accepting" }
            } finally {
                attempt.finish()
            }
        }
    }

    /**
     * Hand a completed handshake's link to [seam], or take it down if the seam closed first. Shielded:
     * the link exists whether or not this coroutine is cancelled, and it is either on the seam or
     * disconnected by the time this returns.
     */
    private suspend fun admitOrRelease(seam: NearbySeam, link: ConnectedLink) {
        withContext(NonCancellable) {
            if (seam.admitEndpoint(link)) return@withContext
            try {
                api.disconnect(link.endpointId)
            } catch (failure: Throwable) {
                log.debug { "nearby.host.late-link-release-failed endpoint=${link.endpointId} failure=$failure" }
            }
        }
    }

    /**
     * Join an existing session. Suspends until the full handshake completes on
     * BOTH sides. Accepts any [Tag] (including [us.tractat.kuilt.core.InMemoryTag]).
     *
     * Subscribe to [NearbyApi.endpointFound] BEFORE calling [NearbyApi.startDiscovery]
     * to avoid the emit-before-subscribe race with the fake's [MutableSharedFlow].
     */
    private suspend fun joinSession(advertisement: Tag): Seam {
        val joinerPeerId = freshPeerId()
        val endpointPeers = mutableMapOf<String, PeerId>()
        // See openSession — one registry per weave, shared with the handshake and the seam.
        val registry = PeerIdentityRegistry<String>(joinerPeerId)
        // Single mutex shared with the seam — same pattern as openSession.
        val endpointPeersMutex = Mutex()
        // This weave's roster — see openSession.
        val weavePeers = MutableStateFlow(setOf(joinerPeerId))
        val seamScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

        val seam = NearbySeam(
            selfId = joinerPeerId,
            endpointPeers = endpointPeers,
            endpointPeersMutex = endpointPeersMutex,
            registry = registry,
            api = api,
            weavePeers = weavePeers,
            scope = seamScope,
            maxChunkPayload = maxChunkPayload,
            msgIdCounter = MsgIdCounter(),
            policy = policy,
        )

        try {
            // Subscribe BEFORE starting discovery to avoid the emit-before-subscribe race.
            // (The fake emits EndpointFound synchronously from startDiscovery on shared flow.)
            val hostEndpointId = awaitFirstEndpointFoundThen(seamScope) {
                api.startDiscovery(serviceId)
            }

            val machine = ConnectStateMachine(
                selfId = joinerPeerId,
                api = api,
                endpointId = hostEndpointId,
                serviceId = serviceId,
                handshakeTimeout = handshakeTimeout,
                registry = registry,
            )

            // run() subscribes the handshake collectors before triggering requestConnection.
            val joinLink = handshakes.dialling(hostEndpointId) {
                machine.run(seamScope) {
                    api.requestConnection(advertisement.sessionName, hostEndpointId)
                }
            }
            seam.admitEndpoint(joinLink)

            // If this loom's own host has a handshake in flight, it may be the other end of this one.
            handshakes.awaitIncomingSettled()
            return seam
        } catch (failure: Throwable) {
            discardUnreturnedSeam(seam)
            throw failure
        } finally {
            stopDiscoveryBestEffort()
        }
    }

    /**
     * Close a seam `weave` wove but will never return: its scope is parentless, so nothing else would
     * ever end its collectors. Shielded, because the throwable being handled may be this coroutine's
     * own cancellation and `close` suspends, and a plain `catch` for the reason `NwLoom` gives for the
     * same shape: inside the shield a cancellation can only be one `close` minted, and rethrowing it
     * would replace the failure the caller is owed.
     */
    private suspend fun discardUnreturnedSeam(seam: Seam) {
        withContext(NonCancellable) {
            try {
                seam.close(CloseReason.Unreachable)
            } catch (failure: Throwable) {
                log.debug { "nearby.loom.discard-close-failed self=${seam.selfId.value} failure=$failure" }
            }
        }
    }

    private suspend fun stopDiscoveryBestEffort() {
        withContext(NonCancellable) {
            try {
                api.stopDiscovery()
            } catch (failure: Throwable) {
                log.debug { "nearby.loom.stop-discovery-failed failure=$failure" }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Subscribe to [NearbyApi.endpointFound], invoke [trigger], then await the
     * first endpoint ID. Subscribing before triggering prevents a lost-event race
     * when the flow has no replay buffer.
     */
    private suspend fun awaitFirstEndpointFoundThen(
        scope: CoroutineScope,
        trigger: suspend () -> Unit,
    ): String {
        val deferred = CompletableDeferred<String>()
        // UNDISPATCHED so we subscribe before trigger() emits EndpointFound.
        val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.endpointFound.collect { event ->
                if (!deferred.isCompleted) deferred.complete(event.endpointId)
            }
        }
        trigger()
        return try {
            deferred.await()
        } finally {
            job.cancel()
        }
    }

    public companion object {
        /**
         * The roles a woven [NearbySeam] holds regardless of what the radios are doing: it carries
         * application frames, and that stays true whether the link came up over Bluetooth or Wi-Fi.
         *
         * The two MEDIUM roles are deliberately **not** here — they are live, folded on per reading
         * from [NearbyRadioState.radioRoles] (#1543), so a seam on a device with Bluetooth switched
         * off stops claiming [TransportRole.Bluetooth]. Contrast [NEARBY_ROLES], which keeps naming
         * both media because [capability] answers "what can this fabric do", not "what is on now".
         */
        internal val NEARBY_BASE_ROLES: Set<TransportRole> = setOf(TransportRole.Data)

        /**
         * The fabric's full STATIC role set, as reported by [capability]: [NEARBY_BASE_ROLES] plus
         * both media Nearby Connections can use. A capability answer about the *fabric*, not about
         * this device right now — see [NEARBY_BASE_ROLES].
         */
        internal val NEARBY_ROLES: Set<TransportRole> =
            NEARBY_BASE_ROLES + setOf(TransportRole.Bluetooth, TransportRole.WifiDirect)

        /** Default Nearby Connections service ID. */
        public const val DEFAULT_SERVICE_ID: String = "us.tractat.kuilt.nearby"

        /**
         * Default ceiling on **one connection's** Nearby handshake.
         *
         * Deliberately *not* [us.tractat.kuilt.core.LoomDefaults.WEAVE_TIMEOUT], despite
         * carrying the same number today. A weave timeout bounds a whole *rendezvous* —
         * discovery, dialling and handshake — and this one cannot: on the discoverer path
         * [NearbyApi.startDiscovery] and the wait for the first `EndpointFound` complete
         * **before** the clock starts, and on the advertiser path `weave` has already
         * returned its seam, so nothing the ceiling covers can fail a `weave` call at all, and
         * the clock starts only when a joiner initiates: the wait for one is unbounded (#2811).
         * Naming it `weaveTimeout` would promise a bound on `weave` that no path delivers
         * (#1430). It belongs with the `handshakeTimeout` family — a ceiling on a single
         * connection — and moves independently of the shared rendezvous default.
         */
        public val DEFAULT_HANDSHAKE_TIMEOUT: Duration = 30.seconds
    }
}

/**
 * What the two roles on one loom need to know about each other, since both read the same radio.
 *
 * Two facts, under one lock because a claim reads the first and writes the second:
 *  - the endpoints this loom's `join` is dialling, which the host must not claim. The radio reports
 *    those through the same callback as incoming joiners, so a host left to claim first-come weaves
 *    itself to a device that never asked to join it. An endpoint is listed once per dial, since two
 *    concurrent joins can dial one endpoint and the first to finish must not unlist the other's.
 *  - how many incoming handshakes are in flight, which a finishing `join` waits out.
 */
private class LocalHandshakes {
    private val lock = reentrantLock()
    private val dialled = mutableListOf<String>()
    private val incomingInFlight = MutableStateFlow(0)

    suspend fun <T> dialling(endpoint: String, handshake: suspend () -> T): T {
        lock.withLock { dialled += endpoint }
        return try {
            handshake()
        } finally {
            lock.withLock { dialled -= endpoint }
        }
    }

    suspend fun awaitIncomingSettled() {
        incomingInFlight.first { it == 0 }
    }

    fun incomingAttempt(): IncomingAttempt = IncomingAttempt()

    /**
     * One pass of a host's acceptance. Counts as in flight from its claim to [finish], which the host
     * calls only after the link is on its seam, so a `join` that waited also finds the host's roster
     * populated. [finish] runs on every exit, cancellation included, or a `join` would wait forever.
     */
    inner class IncomingAttempt {
        private var counted = false

        fun tryClaim(endpoint: String): Boolean =
            lock.withLock {
                if (endpoint in dialled) return false
                if (!counted) {
                    counted = true
                    incomingInFlight.update { it + 1 }
                }
                true
            }

        fun finish() {
            lock.withLock {
                if (counted) {
                    counted = false
                    incomingInFlight.update { it - 1 }
                }
            }
        }
    }
}
