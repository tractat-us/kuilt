package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag

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
 * role ([join]) through the same [NearbyApi]. Both seams share a single
 * [sharedPeers] StateFlow. This matches the [us.tractat.kuilt.core.InMemoryLoom]
 * pattern and lets the conformance suite run a "one loom, one host, one joiner"
 * scenario.
 *
 * @param api              [NearbyApi] to use (real GMS or fake for tests).
 * @param serviceId        Nearby Connections service ID. Must match on both devices.
 * @param timeoutMs        Handshake timeout forwarded to [ConnectStateMachine].
 * @param maxChunkPayload  Per-chunk payload cap forwarded to [ChunkCodec].
 */
public class NearbyLoom(
    private val api: NearbyApi,
    private val serviceId: String = DEFAULT_SERVICE_ID,
    private val timeoutMs: Long = 30_000L,
    private val maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
) : Loom {

    // Shared peer set — all seams on this loom observe the same StateFlow.
    private val sharedPeers = MutableStateFlow<Set<PeerId>>(emptySet())
    private val mutex = Mutex()

    // Per-instance counter — uniqueness within this loom suffices.
    private var peerCounter = 0

    // Stored after open(); used to notify join() when the host side completes.
    private var hostLinkDeferred: CompletableDeferred<ConnectedLink>? = null

    override fun availability(): FabricAvailability = api.availability()

    /**
     * Start advertising and return a [Seam] immediately.
     *
     * A background coroutine watches for the first incoming connection. Once the
     * joiner's handshake completes, the host seam's endpointPeers and sharedPeers
     * are updated and [hostLinkDeferred] resolves so [join] can return.
     *
     * The background scope is derived from the caller's coroutine context so it
     * inherits the test dispatcher and is cleaned up when the seam is closed.
     */
    override suspend fun open(config: Pattern): Seam {
        val peerId = freshPeerId()
        val endpointPeers = mutableMapOf<String, PeerId>()
        // Derive from caller so background work runs on the test dispatcher.
        val seamScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

        val seam = NearbySeam(
            selfId = peerId,
            endpointPeers = endpointPeers,
            api = api,
            sharedPeers = sharedPeers,
            scope = seamScope,
            maxChunkPayload = maxChunkPayload,
            msgIdCounter = MsgIdCounter(),
        )
        val linkDeferred = CompletableDeferred<ConnectedLink>()
        mutex.withLock {
            hostLinkDeferred = linkDeferred
        }

        sharedPeers.update { it + peerId }

        api.startAdvertising(config.displayName, serviceId)

        // Background: accept first joiner, exchange identity, update host seam.
        // UNDISPATCHED so the host's handshake collectors subscribe synchronously
        // during open() — before any joiner's requestConnection emits events.
        seamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                val machine = ConnectStateMachine(
                    selfId = peerId,
                    api = api,
                    endpointId = null,
                    serviceId = serviceId,
                    timeoutMs = timeoutMs,
                )
                // Advertiser: already advertising, so no kickoff — just await a peer.
                val link = machine.run(this) {}
                mutex.withLock {
                    endpointPeers[link.endpointId] = link.remotePeerId
                    sharedPeers.update { it + link.remotePeerId }
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
    override suspend fun join(advertisement: Tag): Seam {
        val joinerPeerId = freshPeerId()
        val endpointPeers = mutableMapOf<String, PeerId>()
        val seamScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

        val seam = NearbySeam(
            selfId = joinerPeerId,
            endpointPeers = endpointPeers,
            api = api,
            sharedPeers = sharedPeers,
            scope = seamScope,
            maxChunkPayload = maxChunkPayload,
            msgIdCounter = MsgIdCounter(),
        )
        sharedPeers.update { it + joinerPeerId }

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
            timeoutMs = timeoutMs,
        )

        // run() subscribes the handshake collectors before triggering requestConnection.
        val joinLink = machine.run(seamScope) {
            api.requestConnection(advertisement.displayName, hostEndpointId)
        }
        endpointPeers[joinLink.endpointId] = joinLink.remotePeerId

        // Wait for the host side to complete — ensures host.peers is populated.
        val hostDeferred = mutex.withLock { hostLinkDeferred }
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

    private fun freshPeerId(): PeerId {
        peerCounter++
        return PeerId("nearby-peer-$peerCounter")
    }

    public companion object {
        /** Default Nearby Connections service ID. */
        public const val DEFAULT_SERVICE_ID: String = "us.tractat.kuilt.nearby"
    }
}

