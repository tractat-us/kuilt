package us.tractat.kuilt.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacade
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacadeFactory
import us.tractat.kuilt.webrtc.internal.SdpType

/**
 * Paired in-memory facades. Frames sent on one end appear on the other.
 * Offer / answer / ICE round-trip is faked (the SDP and ICE strings are
 * never parsed; whatever the local description "is" is what gets handed
 * to the remote).
 *
 * Wire up via [PairedFacadeFactory] which returns two factories whose
 * created facades are connected to each other.
 *
 * Data delivery (ByteArray) uses bounded [Spool]s, mirroring the production
 * [BrowserRtcFacadeFactory] channels. Signaling ([SignalingMessage.IceCandidate])
 * remains on unbounded channels — control-plane, out of scope for backpressure.
 *
 * @param policy Governs the [Spool] capacity and overflow behaviour for data frames.
 *   Defaults to [DeliveryPolicy.Reliable].
 */
internal class PairedFacadeFactory private constructor(
    private val side: Side,
    private val spools: Pair<Spool<ByteArray>, Spool<ByteArray>>,
    private val dataChannelOpen: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>,
    private val remoteClose: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>,
) : RtcPeerConnectionFacadeFactory {
    private enum class Side { Left, Right }

    /**
     * This side's live ICE state, shared by every facade this factory creates.
     *
     * Starts `null` — "nothing observed" — deliberately, and **not** at
     * [IceConnectionState.Connected]. A fake pre-wired to a connected reading would hand every
     * seam a live verdict for free: `WebRTCPeerLinkCapabilityTest`'s tracking assertions would
     * pass against an implementation that only ever read the value once at construction, and
     * `WebRTCConformanceTest`'s `reportsLiveCapability = true` branch would be satisfied by
     * nothing at all. The unobserved floor is the only starting value at which those assertions
     * can still fail. Drive it with [emitIceConnectionState].
     */
    private val _iceConnectionState = MutableStateFlow<IceConnectionState?>(null)

    /**
     * Test hook for #1544: drive a live ICE transition (checks starting, a candidate pair
     * succeeding, connectivity dropping, the agent giving up) directly, under virtual time. Sets
     * the latest-value ICE STATE for every facade this factory has created or will create; the
     * seam folds it into its live [us.tractat.kuilt.core.Seam.capability]. `null` restores
     * "nothing observed".
     *
     * The fake twin of `BrowserRtcFacade`'s `oniceconnectionstatechange` handler — that the
     * browser actually *emits* is provable only in a real `RTCPeerConnection`, never here.
     */
    fun emitIceConnectionState(state: IceConnectionState?) {
        _iceConnectionState.value = state
    }

    /**
     * Kill the data channel under **both** sides of this pair with no `close()` anywhere: complete
     * each side's remote-close signal directly, which is what a browser reports through
     * `RTCDataChannel.onclose` when the connection dies rather than when the application asks.
     * `WebRTCPeerLink`'s `awaitDataChannelClose()` watcher then tears each seam with
     * [us.tractat.kuilt.core.CloseReason.RemoteRequested] and completes `incoming` — the
     * remote-disconnect half of the `incoming`-completes-on-`Torn` contract (#1442).
     *
     * **Deliberately not routed through [FakeFacade.close]**, which is the local-close path: it also
     * closes the outbound [Spool], and a `Seam.close()` on top of it would latch
     * [us.tractat.kuilt.core.CloseReason.Normal]. Completing the deferreds is the *only* way to make
     * this fabric see a death it did not ask for.
     *
     * Both factories in a [pair] share one `remoteClose` pair, so one call drops both sides and a
     * second call on the sibling factory would find nothing left — which is what makes the count
     * below meaningful rather than decorative.
     *
     * @return the number of sides **actually** severed (0, 1 or 2), never a bare `true`.
     *   `CompletableDeferred.complete` answers `false` for a signal that had already fired, so this
     *   counts real severances. The obligation this unblocks reads only *terminal* state, so it
     *   would pass just as happily on a pair that was already dead, crediting a tear this fake did
     *   not cause. Modelled on `FakeNwRadio.dropAllLinks`, which returns a count for the same reason.
     */
    fun dropTransport(): Int =
        listOf(remoteClose.first, remoteClose.second).count { it.complete(Unit) }

    override fun create(
        iceConfig: IceConfig,
        hostInitiated: Boolean,
    ): RtcPeerConnectionFacade {
        // Left facade sends into rightInbound; reads from leftInbound.
        // Right facade sends into leftInbound; reads from rightInbound.
        val (outboundSpool, inboundSpool) =
            when (side) {
                Side.Left -> spools.second to spools.first
                Side.Right -> spools.first to spools.second
            }
        val (localOpen, remoteOpen) =
            when (side) {
                Side.Left -> dataChannelOpen.first to dataChannelOpen.second
                Side.Right -> dataChannelOpen.second to dataChannelOpen.first
            }
        val (localClose, remoteClosePeer) =
            when (side) {
                Side.Left -> remoteClose.first to remoteClose.second
                Side.Right -> remoteClose.second to remoteClose.first
            }
        return FakeFacade(
            outboundSpool,
            inboundSpool,
            localOpen,
            remoteOpen,
            localClose,
            remoteClosePeer,
            _iceConnectionState.asStateFlow(),
        )
    }

    companion object {
        /** Returns (leftFactory, rightFactory). One facade created from each pairs with the other. */
        fun pair(policy: DeliveryPolicy = DeliveryPolicy.Reliable): Pair<PairedFacadeFactory, PairedFacadeFactory> {
            // leftInbound: right writes, left reads. rightInbound: left writes, right reads.
            val leftInbound = Spool<ByteArray>(policy)
            val rightInbound = Spool<ByteArray>(policy)
            val leftOpen = CompletableDeferred<Unit>()
            val rightOpen = CompletableDeferred<Unit>()
            val leftClose = CompletableDeferred<Unit>()
            val rightClose = CompletableDeferred<Unit>()
            val left =
                PairedFacadeFactory(
                    Side.Left,
                    leftInbound to rightInbound,
                    leftOpen to rightOpen,
                    leftClose to rightClose,
                )
            val right =
                PairedFacadeFactory(
                    Side.Right,
                    leftInbound to rightInbound,
                    leftOpen to rightOpen,
                    leftClose to rightClose,
                )
            return left to right
        }
    }
}

private class FakeFacade(
    private val outbound: Spool<ByteArray>,
    private val inbound: Spool<ByteArray>,
    private val localOpen: CompletableDeferred<Unit>,
    private val remoteOpen: CompletableDeferred<Unit>,
    private val localClose: CompletableDeferred<Unit>,
    private val remoteClose: CompletableDeferred<Unit>,
    override val iceConnectionState: StateFlow<IceConnectionState?>,
) : RtcPeerConnectionFacade {
    private val iceCandidates = Channel<SignalingMessage.IceCandidate>(Channel.UNLIMITED)
    private val failure = CompletableDeferred<Throwable>()

    override val localIceCandidates: Flow<SignalingMessage.IceCandidate> = iceCandidates.consumeAsFlow()
    override val incomingBytes: Flow<ByteArray> = inbound.incoming

    override suspend fun awaitDataChannelOpen() {
        // Both sides must call createOffer/createAnswer + setLocalDescription
        // before "open" fires. We approximate by completing as soon as both
        // sides have produced an SDP — which the test setup does in order.
        localOpen.await()
    }

    override suspend fun awaitConnectionFailure(): Throwable = failure.await()

    override suspend fun awaitDataChannelClose() = remoteClose.await()

    override suspend fun createOffer(): String = "fake-offer-sdp"

    override suspend fun createAnswer(): String = "fake-answer-sdp"

    override suspend fun setLocalDescription(
        sdp: String,
        type: SdpType,
    ) {
        // Both sides setting local description = data channel becomes open.
        // We tie 'open' to setLocalDescription on this side AND remote side.
        markLocalReady()
    }

    override suspend fun setRemoteDescription(
        sdp: String,
        type: SdpType,
    ) {
        // Receiving the remote description means our partner has finished
        // its setLocalDescription. Flip the partner's local-open flag.
        markRemoteReady()
    }

    override suspend fun addIceCandidate(candidate: SignalingMessage.IceCandidate) {
        // No-op for the fake.
    }

    override suspend fun sendBytes(bytes: ByteArray) {
        outbound.deliver(bytes)
    }

    override suspend fun close() {
        if (!localClose.isCompleted) {
            localClose.complete(Unit)
            // Signal the partner that we closed, and complete the partner's incoming flow.
            remoteClose.complete(Unit)
        }
        outbound.close()
    }

    private fun markLocalReady() {
        if (!localOpen.isCompleted) localOpen.complete(Unit)
    }

    private fun markRemoteReady() {
        if (!remoteOpen.isCompleted) remoteOpen.complete(Unit)
    }
}
