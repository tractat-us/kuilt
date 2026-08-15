package us.tractat.kuilt.webrtc.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.webrtc.UNOBSERVED_ICE_AVAILABILITY
import us.tractat.kuilt.webrtc.WEBRTC_ROLES
import us.tractat.kuilt.webrtc.toAvailability

private val log = KotlinLogging.logger("us.tractat.kuilt.webrtc.internal.WebRTCPeerLink")

/**
 * [Seam] implementation backed by an open [RtcPeerConnectionFacade] data channel.
 *
 * Stamp each incoming frame with the resolved [senderIdDeferred] as the sender and
 * a monotonically increasing sequence number. The data channel is point-to-point,
 * so [broadcast] and [sendTo] are equivalent.
 *
 * [userFrames] is the post-ID-exchange byte flow — frames after the peer-identity
 * handshake frame. The factory performs the ID exchange and passes the remaining
 * frames here. Defaults to [facade.incomingBytes] for tests that construct
 * [WebRTCPeerLink] directly with pre-coordinated [remoteId] values.
 *
 * [senderIdDeferred] is the remote peer's actual [PeerId], resolved asynchronously
 * once the ID-exchange frame arrives. Defaults to an immediately-completed deferred
 * holding [remoteId], preserving existing test-construction semantics.
 *
 * @param dispatcher Dispatcher for the internal [CoroutineScope]. Production default is
 *   [Dispatchers.Default]; tests inject [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 */
internal class WebRTCPeerLink(
    override val selfId: PeerId,
    private val remoteId: PeerId,
    private val facade: RtcPeerConnectionFacade,
    private val userFrames: Flow<ByteArray> = facade.incomingBytes,
    private val senderIdDeferred: Deferred<PeerId> = CompletableDeferred(remoteId),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Seam {
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sequenceCounter = SequenceCounter()
    private val _peers = MutableStateFlow(setOf(selfId, remoteId))

    override val peers: StateFlow<Set<PeerId>> get() = _peers

    // WebRTC data channel is open at construction — fabric is immediately live.
    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> get() = _state

    // Live capability (#1544): the ROLES are constant (a WebRTC data channel does not stop being
    // one when ICE drops — see [WEBRTC_ROLES]) and the AVAILABILITY is driven by the facade's ICE
    // observer, whose browser binding is `BrowserRtcFacade`'s `oniceconnectionstatechange` handler.
    //
    // The availability is ONLY ever set from an observed ICE reading (#1712): it starts at
    // [UNOBSERVED_ICE_AVAILABILITY] and there is no static value anywhere for it to fall back on,
    // by construction — [WEBRTC_ROLES] carries roles only, and the loom's own
    // `capability()` answers the *platform-support* question rather than a live one.
    //
    // A MutableStateFlow so the write from the single [iceConnectionStateLoop] collector is
    // thread-safe under any dispatcher.
    private val _capability = MutableStateFlow(unobservedCapability)
    override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

    override val incoming: Flow<Swatch> = channelFlow {
        val frames = launch {
            userFrames.collect { bytes ->
                send(Swatch(payload = bytes, sender = senderIdDeferred.await(), sequence = sequenceCounter.next()))
            }
        }
        _state.first { it is SeamState.Torn }
        frames.cancel()
    }

    private var closed = false

    init {
        log.debug { "Seam created self=$selfId remote=$remoteId" }
        // Reconcile the resolved remote PeerId into the roster once the ID-exchange
        // completes: swap the construction-time placeholder for the peer's real
        // selfId, so `peers` reports the true id and `sendTo(realId)` succeeds.
        // Guarded on state: a tear that already collapsed the roster to {selfId}
        // wins — the reconcile must not resurrect a departed peer on a Torn seam.
        scope.launch {
            val resolved = senderIdDeferred.await()
            _peers.update { current ->
                if (_state.value is SeamState.Torn) current else current - remoteId + resolved
            }
            log.debug { "Seam roster reconciled self=$selfId placeholder=$remoteId resolved=$resolved" }
        }
        // Shrink the peer set when the remote closes the channel.
        scope.launch {
            facade.awaitDataChannelClose()
            log.debug { "Seam data channel closed by remote self=$selfId remote=$remoteId" }
            _peers.value = setOf(selfId)
            _state.value = SeamState.Torn(CloseReason.RemoteRequested)
        }
        // UNDISPATCHED, and specifically for a StateFlow reason: subscribing synchronously means
        // the observer's CURRENT value is folded in before construction returns, so a seam woven
        // onto a connection that is already up never transiently publishes the unobserved floor.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { iceConnectionStateLoop() }
    }

    /**
     * Fold the connection's live [RtcPeerConnectionFacade.iceConnectionState] into [capability].
     *
     * A `null` reading means "nothing observed" — the binding wired no observer, or the browser
     * reported a state outside the W3C vocabulary — so we publish [unobservedCapability]: the
     * fabric's roles with an honest [us.tractat.kuilt.core.FabricAvailability.Unknown], never a
     * guessed verdict (#1712). A non-null reading supplies the availability via
     * [toAvailability]; the roles are unchanged either way, because ICE failing does not make this
     * a different kind of transport.
     *
     * The write goes to the seam-owned [_capability], so this single collector is the sole writer
     * and no lock is needed. Terminates with [scope] on close — this loop holds no state to unwind.
     */
    private suspend fun iceConnectionStateLoop() {
        facade.iceConnectionState.collect { ice ->
            _capability.value =
                if (ice == null) {
                    unobservedCapability
                } else {
                    TransportCapability(roles = WEBRTC_ROLES, availability = ice.toAvailability())
                }
        }
    }

    /**
     * The capability of a seam with **no live ICE reading**: the fabric's roles, but an honest
     * `Unknown` availability. See [UNOBSERVED_ICE_AVAILABILITY] for why this cannot fall back on
     * anything stronger.
     */
    private val unobservedCapability: TransportCapability
        get() = TransportCapability(roles = WEBRTC_ROLES, availability = UNOBSERVED_ICE_AVAILABILITY)

    private val closedMessage get() = "WebRTC seam for $selfId is Torn"

    /**
     * Best-effort: silently drops the frame (with a warning) when no remote peer is connected.
     * Use [sendTo] for addressed delivery that throws [PeerNotConnected] on a missing peer.
     *
     * Throws [IllegalStateException] on a [SeamState.Torn] seam, matching every other fabric.
     */
    override suspend fun broadcast(payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        if (_peers.value.none { it != selfId }) {
            log.warn { "webrtc.send dropped — no connected peers selfId=${selfId.value} bytes=${payload.size}" }
            return
        }
        facade.sendBytes(payload)
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        if (peer !in resolvedRoster()) throw PeerNotConnected(peer)
        facade.sendBytes(payload)
    }

    /**
     * The roster with the remote's real [PeerId] resolved. Awaits the ID-exchange
     * if it is still pending, so a `sendTo` addressed to the peer's real id — read
     * out-of-band before the background reconciliation lands in [_peers] — succeeds
     * rather than racing the placeholder swap. The remote's id is intrinsic to a
     * 2-peer point-to-point link's handshake, so the await is bounded in practice.
     */
    private suspend fun resolvedRoster(): Set<PeerId> = setOf(selfId, senderIdDeferred.await())

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        log.debug { "Seam closing self=$selfId remote=$remoteId reason=$reason" }
        _state.value = SeamState.Torn(reason)
        try {
            facade.close()
        } finally {
            scope.cancel()
        }
    }
}

private class SequenceCounter {
    private var next: Long = 0L

    fun next(): Long = next++
}
