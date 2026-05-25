package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [PeerLink] wrapper that injects configurable faults for use in tests.
 *
 * Faults are driven by a [FaultProfile] which can be swapped atomically at
 * any point during a test via [setFaultProfile]. The profile change takes
 * effect on the next frame — frames already in-flight at the moment of the
 * switch complete under the old profile.
 *
 * **Determinism guarantee:** every probabilistic and randomised fault takes
 * an explicit seed. No wall-clock time is consumed — delays go through
 * [kotlinx.coroutines.delay] so [kotlinx.coroutines.test.runTest] controls
 * virtual time.
 *
 * **Inspection hooks:** [framesDropped], [framesDelayed], and
 * [framesDelivered] counters are updated atomically so tests can assert on
 * fault behaviour without inspecting internal channels.
 *
 * Consumed by [#1067], [#1070], [#1074] test suites. Exposes the same
 * [PeerLink] contract as [InMemoryPeerLinkFactory]-produced links.
 */
class FaultyPeerLink(
    private val delegate: PeerLink,
    private val scope: CoroutineScope,
    initialProfile: FaultProfile = FaultProfile.Healthy,
) : PeerLink {
    private val faultState = FaultState(initialProfile)
    private val mutex = Mutex()

    // Incoming channel — inbound fault logic runs here before delivery.
    private val incomingChannel = Channel<OpaqueFrame>(capacity = Channel.UNLIMITED)

    // Counters
    private var _framesDropped = 0L
    private var _framesDelayed = 0L
    private var _framesDelivered = 0L

    val framesDropped: Long get() = _framesDropped
    val framesDelayed: Long get() = _framesDelayed
    val framesDelivered: Long get() = _framesDelivered

    init {
        // Pipe from the delegate's incoming flow through fault injection.
        scope.launch {
            delegate.incoming.collect { frame -> injectInbound(frame) }
            incomingChannel.close()
        }
    }

    /** Replace the active [FaultProfile] atomically. */
    fun setFaultProfile(profile: FaultProfile) {
        faultState.profile = profile
    }

    /** Shorthand for [setFaultProfile] with [FaultProfile.Healthy]. */
    fun heal() = setFaultProfile(FaultProfile.Healthy)

    /** Shorthand for [setFaultProfile] with [FaultProfile.DropAll]. */
    fun partition(direction: Direction = Direction.Both) = setFaultProfile(FaultProfile.DropAll(direction))

    // ── PeerLink ─────────────────────────────────────────────────────────────

    override val selfId: TransportPeerId get() = delegate.selfId

    override val peers: StateFlow<Set<TransportPeerId>> get() = delegate.peers

    override val incoming: Flow<OpaqueFrame> = incomingChannel.receiveAsFlow()

    override suspend fun broadcast(payload: ByteArray) {
        val decision = mutex.withLock { faultState.evaluateOutbound(payload) }
        applyOutboundDecision(decision) { delegate.broadcast(it) }
    }

    override suspend fun sendTo(
        peer: TransportPeerId,
        payload: ByteArray,
    ) {
        val decision = mutex.withLock { faultState.evaluateOutbound(payload) }
        applyOutboundDecision(decision) { delegate.sendTo(peer, it) }
    }

    override suspend fun close(reason: CloseReason) = delegate.close(reason)

    // ── Internal outbound dispatch ────────────────────────────────────────────

    private suspend fun applyOutboundDecision(
        decision: OutboundDecision,
        send: suspend (ByteArray) -> Unit,
    ) {
        when (decision) {
            is OutboundDecision.Send -> {
                send(decision.payload)
                _framesDelivered++
            }
            is OutboundDecision.Delay -> {
                _framesDelayed++
                delay(decision.delay)
                send(decision.payload)
                _framesDelivered++
            }
            is OutboundDecision.Drop -> {
                _framesDropped++
            }
            is OutboundDecision.Buffer -> {
                // Frame is held in FaultState's reorder window; nothing to do here.
            }
            is OutboundDecision.SendBurst -> {
                for (p in decision.payloads) {
                    send(p)
                    _framesDelivered++
                }
            }
            is OutboundDecision.CloseLink -> {
                delegate.close(decision.reason)
            }
        }
    }

    // ── Internal inbound injection ────────────────────────────────────────────

    private suspend fun injectInbound(frame: OpaqueFrame) {
        val toDeliver = mutex.withLock { faultState.evaluateInbound(frame) }
        val inboundDelay = faultState.inboundDelay(faultState.profile)

        if (toDeliver.isEmpty()) {
            _framesDropped++
            return
        }
        if (inboundDelay != null) {
            _framesDelayed++
            delay(inboundDelay)
        }
        for (f in toDeliver) {
            incomingChannel.trySend(f)
            _framesDelivered++
        }
    }
}

/**
 * A [PeerLinkFactory] wrapper that constructs [FaultyPeerLink] instances.
 *
 * A [defaultProfile] applies to every link the factory creates. Individual
 * links can override their profile via [FaultyPeerLink.setFaultProfile].
 *
 * Useful for fault scenarios where **all** links should start partitioned
 * or delayed, then selectively healed per-peer.
 *
 * [scope] must be a [CoroutineScope] that outlives the test — the standard
 * pattern is to pass the [kotlinx.coroutines.test.TestScope] from [runTest].
 */
class FaultyPeerLinkFactory(
    private val delegate: PeerLinkFactory,
    private val scope: CoroutineScope,
    private val defaultProfile: FaultProfile = FaultProfile.Healthy,
) : PeerLinkFactory {
    private val _links = MutableStateFlow<List<FaultyPeerLink>>(emptyList())

    /** All [FaultyPeerLink] instances created so far, in creation order. */
    val links: List<FaultyPeerLink> get() = _links.value

    override suspend fun open(config: SessionConfig): FaultyPeerLink {
        val inner = delegate.open(config)
        return wrap(inner)
    }

    override suspend fun join(advertisement: PeerAdvertisement): FaultyPeerLink {
        val inner = delegate.join(advertisement)
        return wrap(inner)
    }

    /** Apply [profile] to every link the factory has created so far. */
    fun setFaultProfileOnAll(profile: FaultProfile) {
        _links.value.forEach { it.setFaultProfile(profile) }
    }

    private fun wrap(delegate: PeerLink): FaultyPeerLink {
        val link = FaultyPeerLink(delegate, scope, defaultProfile)
        _links.value = _links.value + link
        return link
    }
}
