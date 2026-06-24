package us.tractat.kuilt.webrtc.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.webrtc.IceConfig
import us.tractat.kuilt.webrtc.IceServer
import us.tractat.kuilt.webrtc.IceTransportPolicy
import us.tractat.kuilt.webrtc.SignalingMessage
import kotlin.coroutines.CoroutineContext

private val log = KotlinLogging.logger {}

/**
 * Browser-backed [RtcPeerConnectionFacade] factory. Constructs a real
 * [RTCPeerConnection] and wires its callbacks to the [RtcPeerConnectionFacade]
 * interface so the [HandshakeRunner] state machine can drive it.
 *
 * Event handlers are installed via `@JsFun`-annotated bridge functions (e.g.
 * [pcSetOnIceCandidate], [dcSetOnOpen]) rather than direct Kotlin-lambda
 * assignment to external-class vars. The bridge approach is reliable in
 * Kotlin/Wasm because the JS closure extracts primitives / [kotlin.js.JsAny]
 * values before calling the Kotlin handler — avoiding the unreliable
 * external-interface wrapper path that direct assignment uses.
 *
 * @param policy Governs the inbound [Spool]'s capacity and overflow behaviour.
 *   Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 * @param dispatcher The context for the delivery drain coroutine (scheduling only).
 *   Production callers use the default [Dispatchers.Default]; tests pass a context
 *   derived from the test scheduler so virtual-time control works.
 */
internal class BrowserRtcFacadeFactory(
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : RtcPeerConnectionFacadeFactory {
    override fun create(
        iceConfig: IceConfig,
        hostInitiated: Boolean,
    ): RtcPeerConnectionFacade {
        val pc = RTCPeerConnection(buildConfiguration(iceConfig))
        val dataChannel =
            if (hostInitiated) {
                pc.createDataChannel("kuilt.data", rtcDataChannelInit())
            } else {
                null
            }
        return BrowserRtcFacade(pc, dataChannel, policy, dispatcher)
    }

    private fun buildConfiguration(iceConfig: IceConfig): RTCConfiguration {
        val config = rtcConfiguration()
        val servers = kotlin.js.JsArray<RTCIceServer>()
        iceConfig.iceServers.forEachIndexed { index, server ->
            val js = rtcIceServer()
            when (server) {
                is IceServer.Stun -> js.urls = server.url
                is IceServer.Turn -> {
                    js.urls = server.url
                    js.username = server.username
                    js.credential = server.credential
                }
            }
            servers[index] = js
        }
        config.iceServers = servers
        config.iceTransportPolicy =
            when (iceConfig.iceTransportPolicy) {
                IceTransportPolicy.All -> "all"
                IceTransportPolicy.Relay -> "relay"
            }
        return config
    }
}

private class BrowserRtcFacade(
    private val pc: RTCPeerConnection,
    initialDataChannel: RTCDataChannel?,
    policy: DeliveryPolicy,
    dispatcher: CoroutineContext,
) : RtcPeerConnectionFacade {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val iceCandidatesChan = Channel<SignalingMessage.IceCandidate>(Channel.UNLIMITED)

    // Bounded staging channel: the RTCDataChannel onmessage JS callback deposits frames here
    // non-suspendingly via trySend. The single drain coroutine forwards them to the spool in
    // FIFO order — matching the bridge pattern from BridgePeerLink / MCSessionLink.
    private val bridge: Channel<ByteArray> =
        Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val spool = Spool<ByteArray>(policy)

    private val dataChannelOpen = CompletableDeferred<Unit>()
    private val dataChannelClosed = CompletableDeferred<Unit>()
    private val connectionFailed = CompletableDeferred<Throwable>()

    private var dataChannel: RTCDataChannel? = initialDataChannel?.also(::installDataChannelHandlers)

    override val localIceCandidates: Flow<SignalingMessage.IceCandidate> =
        iceCandidatesChan.consumeAsFlow()
    override val incomingBytes: Flow<ByteArray> = spool.incoming

    init {
        // Single drain coroutine: forwards frames from the JS-callback bridge to the spool in
        // FIFO order. A single coroutine (not one per frame) preserves delivery ordering.
        scope.launch {
            for (frame in bridge) {
                spool.deliver(frame)
            }
        }

        pcSetOnIceCandidate(pc) { cand ->
            log.debug { "rtc localIceCandidate sdpMid=${sdpMidString(cand)}" }
            iceCandidatesChan.trySend(
                SignalingMessage.IceCandidate(
                    candidate = candidateString(cand),
                    sdpMid = sdpMidString(cand),
                    sdpMLineIndex = sdpMLineIndexInt(cand),
                ),
            )
        }
        pcSetOnIceConnectionStateChange(pc) { state ->
            log.debug { "rtc iceConnectionState=$state dataChannelOpenCompleted=${dataChannelOpen.isCompleted}" }
            if (state == "failed" && !dataChannelOpen.isCompleted) {
                connectionFailed.complete(IllegalStateException("ICE failed before data channel opened"))
            }
        }
        pcSetOnDataChannel(pc) { ch ->
            log.debug { "rtc ondatachannel readyState=${ch.readyState}" }
            dataChannel = ch
            installDataChannelHandlers(ch)
        }
    }

    private fun installDataChannelHandlers(ch: RTCDataChannel) {
        ch.binaryType = "arraybuffer"
        dcSetOnOpen(ch) {
            log.debug { "rtc dc.onopen — dataChannelOpen.complete" }
            if (!dataChannelOpen.isCompleted) dataChannelOpen.complete(Unit)
        }
        dcSetOnClose(ch) {
            log.debug { "rtc dc.onclose — closing bridge and spool" }
            if (!dataChannelClosed.isCompleted) dataChannelClosed.complete(Unit)
            bridge.close()
            spool.close()
        }
        dcSetOnError(ch) { message ->
            log.warn { "rtc dc.onerror message=$message readyState=${ch.readyState}" }
        }
        dcSetOnMessage(ch) { data ->
            // Non-suspending deposit into the bridge channel. The drain coroutine
            // forwards to spool.deliver in FIFO order.
            bridge.trySend(data.toByteArray())
        }
    }

    override suspend fun awaitDataChannelOpen() {
        dataChannelOpen.await()
    }

    override suspend fun awaitConnectionFailure(): Throwable = connectionFailed.await()

    override suspend fun awaitDataChannelClose() = dataChannelClosed.await()

    override suspend fun createOffer(): String = sdpString(pc.createOffer().await())

    override suspend fun createAnswer(): String = sdpString(pc.createAnswer().await())

    override suspend fun setLocalDescription(
        sdp: String,
        type: SdpType,
    ) {
        val init = rtcSessionDescriptionInit()
        init.type = if (type == SdpType.Offer) "offer" else "answer"
        init.sdp = sdp
        pc.setLocalDescription(RTCSessionDescription(init)).await()
    }

    override suspend fun setRemoteDescription(
        sdp: String,
        type: SdpType,
    ) {
        val init = rtcSessionDescriptionInit()
        init.type = if (type == SdpType.Offer) "offer" else "answer"
        init.sdp = sdp
        pc.setRemoteDescription(RTCSessionDescription(init)).await()
    }

    override suspend fun addIceCandidate(candidate: SignalingMessage.IceCandidate) {
        val init = rtcIceCandidateInit()
        init.candidate = candidate.candidate
        init.sdpMid = candidate.sdpMid
        init.sdpMLineIndex = candidate.sdpMLineIndex
        pc.addIceCandidate(RTCIceCandidate(init)).await()
    }

    override suspend fun sendBytes(bytes: ByteArray) {
        val ch = dataChannel ?: error("Data channel not yet established")
        ch.send(bytes.toArrayBuffer())
    }

    override suspend fun close() {
        dataChannel?.close()
        pc.close()
        bridge.close()
        spool.close()
        iceCandidatesChan.close()
    }
}
