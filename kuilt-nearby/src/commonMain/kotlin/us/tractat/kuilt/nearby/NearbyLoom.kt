package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.runCatchingCancellable
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

    // Guards loom-level state: hostLinkDeferred.
    private val loomMutex = Mutex()

    // Stored after open(); used to notify join() when the host side completes.
    private var hostLinkDeferred: CompletableDeferred<ConnectedLink>? = null

    override fun capability(): TransportCapability =
        TransportCapability(roles = NEARBY_ROLES, availability = api.availability())

    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New -> openSession(rendezvous.pattern)
            is Rendezvous.Existing -> joinSession(rendezvous.tag)
        }

    /**
     * Start advertising and return a [Seam] immediately.
     *
     * A background coroutine watches for the first incoming connection. Once the
     * joiner's handshake completes, the host seam's endpointPeers and weavePeers
     * are updated and [hostLinkDeferred] resolves so [join] can return.
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
        )
        val linkDeferred = CompletableDeferred<ConnectedLink>()
        loomMutex.withLock {
            hostLinkDeferred = linkDeferred
        }

        api.startAdvertising(config.sessionName, serviceId)

        // Background: accept first joiner, exchange identity, update host seam.
        // UNDISPATCHED so the host's handshake collectors subscribe synchronously
        // during open() — before any joiner's requestConnection emits events.
        seamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable {
                val machine = ConnectStateMachine(
                    selfId = peerId,
                    api = api,
                    endpointId = null,
                    serviceId = serviceId,
                    handshakeTimeout = handshakeTimeout,
                    registry = registry,
                )
                // Advertiser: already advertising, so no kickoff — just await a peer.
                val link = machine.run(this) {}
                endpointPeersMutex.withLock {
                    endpointPeers[link.endpointId] = link.remotePeerId
                    seam.admitRemote(link.remotePeerId)
                }
                linkDeferred.complete(link)
            }.onFailure { linkDeferred.completeExceptionally(it) }
        }

        return seam
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
        val joinLink = machine.run(seamScope) {
            api.requestConnection(advertisement.sessionName, hostEndpointId)
        }
        endpointPeersMutex.withLock {
            endpointPeers[joinLink.endpointId] = joinLink.remotePeerId
            // The joiner records the host from its OWN completed handshake. It used to learn the
            // host's id by reading the loom-wide flow the host had written — which worked only
            // because the fake harness puts both ends in one process, and is the coupling #1878
            // removes. `admitRemote` rather than a bare flow write because `join` returns right
            // after this: the seam it hands back must already carry the peer and already be Woven,
            // and a flow write alone leaves both owing a dispatch (see NearbySeam.admitRemote).
            seam.admitRemote(joinLink.remotePeerId)
        }

        // Wait for the host side to complete — ensures host.peers is populated.
        val hostDeferred = loomMutex.withLock { hostLinkDeferred }
        hostDeferred?.await()

        api.stopDiscovery()

        return seam
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
         * returned its seam, so nothing the ceiling covers can fail a `weave` call at all.
         * Naming it `weaveTimeout` would promise a bound on `weave` that no path delivers
         * (#1430). It belongs with the `handshakeTimeout` family — a ceiling on a single
         * connection — and moves independently of the shared rendezvous default.
         */
        public val DEFAULT_HANDSHAKE_TIMEOUT: Duration = 30.seconds
    }
}
