package us.tractat.kuilt.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
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
 */
internal class PairedFacadeFactory private constructor(
    private val side: Side,
    private val partner: Pair<Channel<ByteArray>, Channel<ByteArray>>,
    private val dataChannelOpen: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>,
    private val remoteClose: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>,
) : RtcPeerConnectionFacadeFactory {
    private enum class Side { Left, Right }

    override fun create(
        iceConfig: IceConfig,
        hostInitiated: Boolean,
    ): RtcPeerConnectionFacade {
        val (outboundChan, inboundChan) =
            when (side) {
                Side.Left -> partner.first to partner.second
                Side.Right -> partner.second to partner.first
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
        return FakeFacade(outboundChan, inboundChan, localOpen, remoteOpen, localClose, remoteClosePeer)
    }

    companion object {
        /** Returns (leftFactory, rightFactory). One facade created from each pairs with the other. */
        fun pair(): Pair<PairedFacadeFactory, PairedFacadeFactory> {
            val leftToRight = Channel<ByteArray>(Channel.UNLIMITED)
            val rightToLeft = Channel<ByteArray>(Channel.UNLIMITED)
            val leftOpen = CompletableDeferred<Unit>()
            val rightOpen = CompletableDeferred<Unit>()
            val leftClose = CompletableDeferred<Unit>()
            val rightClose = CompletableDeferred<Unit>()
            val left =
                PairedFacadeFactory(
                    Side.Left,
                    leftToRight to rightToLeft,
                    leftOpen to rightOpen,
                    leftClose to rightClose,
                )
            val right =
                PairedFacadeFactory(
                    Side.Right,
                    leftToRight to rightToLeft,
                    leftOpen to rightOpen,
                    leftClose to rightClose,
                )
            return left to right
        }
    }
}

private class FakeFacade(
    private val outbound: Channel<ByteArray>,
    private val inbound: Channel<ByteArray>,
    private val localOpen: CompletableDeferred<Unit>,
    private val remoteOpen: CompletableDeferred<Unit>,
    private val localClose: CompletableDeferred<Unit>,
    private val remoteClose: CompletableDeferred<Unit>,
) : RtcPeerConnectionFacade {
    private val iceCandidates = Channel<SignalingMessage.IceCandidate>(Channel.UNLIMITED)
    private val failure = CompletableDeferred<Throwable>()

    override val localIceCandidates: Flow<SignalingMessage.IceCandidate> = iceCandidates.consumeAsFlow()
    override val incomingBytes: Flow<ByteArray> = inbound.consumeAsFlow()

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
        outbound.send(bytes)
    }

    override suspend fun close() {
        if (!localClose.isCompleted) {
            localClose.complete(Unit)
            // Signal the partner that we closed.
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
