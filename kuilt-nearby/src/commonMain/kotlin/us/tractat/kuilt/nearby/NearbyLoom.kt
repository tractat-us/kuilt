package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
 * ## Single-loom symmetric topology
 * One [NearbyLoom] handles both the advertiser role ([open]) and the discoverer
 * role ([join]) through the same [NearbyApi]. Both seams share a single
 * [sharedPeers] StateFlow. This matches the [us.tractat.kuilt.core.InMemoryLoom]
 * pattern and lets the conformance suite run a "one loom, one host, one joiner"
 * scenario.
 *
 * ## Concurrency
 * [open] returns immediately after starting advertising and launching a background
 * connection-acceptance coroutine. [join] suspends until the full handshake
 * (including identity exchange on BOTH sides) completes — guaranteeing that both
 * seams have consistent [Seam.peers] when [join] returns.
 *
 * ## Endpoint ID filtering
 * Both the host and joiner state machines share one [NearbyApi] in tests (the fake).
 * Events are partitioned by endpoint ID: the joiner's state machine filters on the
 * endpoint ID learned from [EndpointFound]; the host's state machine uses
 * "first-seen" mode (it grabs the first [ConnectionInitiated] endpoint it sees,
 * which the fake emits as the advertiser-side ID first).
 *
 * @param api              [NearbyApi] to use (real GMS or fake for tests).
 * @param serviceId        Nearby Connections service ID. Must match on both devices.
 * @param peerId           This peer's stable identity. Defaults to a counter-based value.
 * @param backgroundScope  Scope for the host-side background listener. Defaults to a
 *                         private [SupervisorJob] scope; pass the test coroutine scope
 *                         to keep everything under [runTest]'s virtual time.
 * @param timeoutMs        Handshake timeout forwarded to [ConnectStateMachine].
 * @param maxChunkPayload  Per-chunk payload cap forwarded to [ChunkCodec].
 */
public class NearbyLoom(
    private val api: NearbyApi,
    private val serviceId: String = DEFAULT_SERVICE_ID,
    private val peerId: PeerId = generatePeerId(),
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val timeoutMs: Long = 30_000L,
    private val maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
) : Loom {

    // Shared peer set — all seams on this loom observe the same StateFlow.
    private val sharedPeers = MutableStateFlow<Set<PeerId>>(emptySet())
    private val mutex = Mutex()

    // Stored after open(); used by join() to update the host seam's endpointPeers.
    private var hostSeam: NearbySeam? = null
    private var hostEndpointPeers: MutableMap<String, PeerId>? = null

    // Resolves when the host-side handshake completes; awaited by join().
    private var hostLinkDeferred: CompletableDeferred<ConnectedLink>? = null

    override fun availability(): FabricAvailability = api.availability()

    /**
     * Start advertising and return a [Seam] immediately.
     *
     * A background coroutine watches for incoming connections. Once a joiner
     * connects, the host seam's [endpointPeers] and [sharedPeers] are updated,
     * and [hostLinkDeferred] is completed so [join] can return.
     */
    override suspend fun open(config: Pattern): Seam {
        val endpointPeers = mutableMapOf<String, PeerId>()
        val seam = NearbySeam(
            selfId = peerId,
            endpointPeers = endpointPeers,
            api = api,
            sharedPeers = sharedPeers,
            scope = backgroundScope,
            maxChunkPayload = maxChunkPayload,
            msgIdCounter = MsgIdCounter(),
        )
        val linkDeferred = CompletableDeferred<ConnectedLink>()
        mutex.withLock {
            hostSeam = seam
            hostEndpointPeers = endpointPeers
            hostLinkDeferred = linkDeferred
        }

        sharedPeers.value = sharedPeers.value + peerId

        api.startAdvertising(config.displayName, serviceId)

        // Background: accept first joiner, exchange identity, update host seam.
        backgroundScope.launch {
            runCatching {
                val machine = ConnectStateMachine(
                    selfId = peerId,
                    api = api,
                    endpointId = null,       // first-seen mode: host doesn't know the joiner's endpoint ID upfront
                    serviceId = serviceId,
                    timeoutMs = timeoutMs,
                )
                val link = machine.await(this)
                mutex.withLock {
                    endpointPeers[link.endpointId] = link.remotePeerId
                    sharedPeers.value = sharedPeers.value + link.remotePeerId
                }
                linkDeferred.complete(link)
            }.onFailure { linkDeferred.completeExceptionally(it) }
        }

        return seam
    }

    /**
     * Join an existing session. Suspends until the full handshake completes on
     * BOTH sides. Accepts any [Tag] (including [us.tractat.kuilt.core.InMemoryTag]).
     */
    override suspend fun join(advertisement: Tag): Seam {
        val joinerPeerId = generatePeerId()
        val endpointPeers = mutableMapOf<String, PeerId>()
        val seam = NearbySeam(
            selfId = joinerPeerId,
            endpointPeers = endpointPeers,
            api = api,
            sharedPeers = sharedPeers,
            scope = backgroundScope,
            maxChunkPayload = maxChunkPayload,
            msgIdCounter = MsgIdCounter(),
        )
        sharedPeers.value = sharedPeers.value + joinerPeerId

        api.startDiscovery(serviceId)

        // Discover the advertiser's endpoint ID, then connect.
        val hostEndpointId = awaitFirstEndpointFound()

        val machine = ConnectStateMachine(
            selfId = joinerPeerId,
            api = api,
            endpointId = hostEndpointId,    // filter mode: we know our target endpoint
            serviceId = serviceId,
            timeoutMs = timeoutMs,
        )

        // Request the connection and run the handshake concurrently.
        val joinLink = coroutineScope {
            api.requestConnection(advertisement.displayName, hostEndpointId)
            machine.await(this)
        }
        endpointPeers[joinLink.endpointId] = joinLink.remotePeerId

        // Wait for the host side to also complete — ensures host.peers.value is populated.
        val hostDeferred = mutex.withLock { hostLinkDeferred }
        hostDeferred?.await()

        api.stopDiscovery()

        return seam
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private suspend fun awaitFirstEndpointFound(): String {
        val deferred = CompletableDeferred<String>()
        val job = backgroundScope.launch {
            api.endpointFound.collect { event ->
                deferred.complete(event.endpointId)
            }
        }
        return try {
            deferred.await()
        } finally {
            job.cancel()
        }
    }

    public companion object {
        /** Default Nearby Connections service ID. */
        public const val DEFAULT_SERVICE_ID: String = "us.tractat.kuilt.nearby"

        private var peerCounter = 0

        internal fun generatePeerId(): PeerId {
            peerCounter++
            return PeerId("nearby-peer-$peerCounter")
        }
    }
}
