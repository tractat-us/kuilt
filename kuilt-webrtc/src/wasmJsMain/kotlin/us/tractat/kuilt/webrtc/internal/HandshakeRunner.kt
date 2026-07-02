package us.tractat.kuilt.webrtc.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.webrtc.SignalingMessage
import us.tractat.kuilt.webrtc.SignalingSession

private val log = KotlinLogging.logger("us.tractat.kuilt.webrtc.internal.HandshakeRunner")

/**
 * Default deadline for the offer/answer/ICE exchange to bring a data channel up.
 *
 * On WASM, the two tabs may stagger by 30–60 s during bundle load, so callers that
 * know the peer may be slow-booting should pass a larger [handshakeTimeoutMs] to
 * [HandshakeRunner.runHost] / [HandshakeRunner.runJoiner].
 */
internal const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L

/**
 * Runs the WebRTC offer / answer / trickle-ICE exchange to bring a
 * [RtcPeerConnectionFacade] from constructed to "data channel open".
 *
 * Drives the offer/answer SDP exchange and trickle-ICE candidate forwarding until
 * the RTCDataChannel transitions to open, then returns.
 */
internal object HandshakeRunner {
    /**
     * Host side: create the offer, send it, then process the joiner's
     * answer and trickled ICE candidates until the data channel opens.
     *
     * Returns the same [facade] on success. Throws if the connection
     * fails before the data channel opens, the handshake exceeds
     * [handshakeTimeoutMs], or signaling drops.
     *
     * @param handshakeTimeoutMs overall deadline for the entire handshake. Defaults to
     *   [DEFAULT_HANDSHAKE_TIMEOUT_MS] (30 s). Increase for environments where the peer
     *   may take longer to boot before joining the room (e.g. WASM bundle load: 30–60 s).
     */
    suspend fun runHost(
        facade: RtcPeerConnectionFacade,
        signaling: SignalingSession,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): RtcPeerConnectionFacade =
        runHandshake(
            role = "host",
            facade = facade,
            signaling = signaling,
            handshakeTimeoutMs = handshakeTimeoutMs,
            // Host must send its offer before the inbound loop starts.
            preamble = {
                val offerSdp = facade.createOffer()
                facade.setLocalDescription(offerSdp, SdpType.Offer)
                log.debug { "handshake host: offer created+set, sending offer" }
                signaling.send(SignalingMessage.Offer(sdp = offerSdp))
            },
        ) { message ->
            when (message) {
                is SignalingMessage.Answer -> {
                    log.debug { "handshake host: received Answer — setting remoteDescription" }
                    facade.setRemoteDescription(message.sdp, SdpType.Answer)
                }
                is SignalingMessage.IceCandidate -> {
                    log.debug { "handshake host: received remoteIceCandidate sdpMid=${message.sdpMid}" }
                    facade.addIceCandidate(message)
                }
                is SignalingMessage.Bye ->
                    throw IllegalStateException("Remote sent Bye during handshake")
                is SignalingMessage.Offer ->
                    throw IllegalStateException("Host received Offer; expected Answer")
                // Role is a relay-level frame consumed before the runner starts.
                // Silently skip if it somehow arrives mid-stream.
                is SignalingMessage.Role -> Unit
            }
        }

    /**
     * Joiner side: wait for the host's offer, answer it, then process
     * trickled ICE candidates until the data channel opens.
     *
     * @param handshakeTimeoutMs overall deadline for the entire handshake. Defaults to
     *   [DEFAULT_HANDSHAKE_TIMEOUT_MS] (30 s). Increase for environments where the peer
     *   may take longer to boot before joining the room (e.g. WASM bundle load: 30–60 s).
     */
    suspend fun runJoiner(
        facade: RtcPeerConnectionFacade,
        signaling: SignalingSession,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): RtcPeerConnectionFacade =
        runHandshake(
            role = "joiner",
            facade = facade,
            signaling = signaling,
            handshakeTimeoutMs = handshakeTimeoutMs,
        ) { message ->
            when (message) {
                is SignalingMessage.Offer -> {
                    log.debug { "handshake joiner: received Offer — creating answer" }
                    facade.setRemoteDescription(message.sdp, SdpType.Offer)
                    val answerSdp = facade.createAnswer()
                    facade.setLocalDescription(answerSdp, SdpType.Answer)
                    log.debug { "handshake joiner: sending Answer" }
                    signaling.send(SignalingMessage.Answer(sdp = answerSdp))
                }
                is SignalingMessage.IceCandidate -> {
                    log.debug { "handshake joiner: received remoteIceCandidate sdpMid=${message.sdpMid}" }
                    facade.addIceCandidate(message)
                }
                is SignalingMessage.Bye ->
                    throw IllegalStateException("Remote sent Bye during handshake")
                is SignalingMessage.Answer ->
                    throw IllegalStateException("Joiner received Answer; expected Offer")
                // Role is a relay-level frame consumed before the runner starts.
                // Silently skip if it somehow arrives mid-stream.
                is SignalingMessage.Role -> Unit
            }
        }

    /**
     * The shared host/joiner handshake body. Launches ICE-candidate forwarding,
     * runs the role-specific [preamble] (the host sends its offer here, strictly
     * before the inbound loop starts), then processes inbound signaling via
     * [inbound] until the data channel opens or the deadline elapses.
     *
     * The [role] string differs only as a log tag ("host" | "joiner").
     */
    private suspend fun runHandshake(
        role: String,
        facade: RtcPeerConnectionFacade,
        signaling: SignalingSession,
        handshakeTimeoutMs: Long,
        preamble: suspend () -> Unit = {},
        inbound: suspend (SignalingMessage) -> Unit,
    ): RtcPeerConnectionFacade =
        coroutineScope {
            log.debug { "handshake $role: starting (timeoutMs=$handshakeTimeoutMs)" }
            // Pipe local ICE candidates out as they're gathered.
            val iceJob =
                launch {
                    facade.localIceCandidates.collect { candidate ->
                        log.debug { "handshake $role: sending localIceCandidate sdpMid=${candidate.sdpMid}" }
                        signaling.send(candidate)
                    }
                }

            // Role-specific setup that must run before inbound collection begins
            // (the host creates and sends its offer here).
            preamble()

            // Process inbound signaling until the data channel opens.
            launch {
                signaling.incoming.collect { message -> inbound(message) }
            }

            try {
                log.debug { "handshake $role: awaiting data channel open" }
                awaitConnected(facade, handshakeTimeoutMs)
                log.info { "handshake $role: data channel OPEN — closing signaling" }
            } finally {
                iceJob.cancel()
                // Do NOT cancel the inbound job here. Calling signaling.close() closes
                // inboundChannel, which causes the receiveAsFlow() collector in the inbound
                // launch to complete *normally*. If we cancel inbound first, the race between
                // CancellationException (from cancel()) and ClosedReceiveChannelException
                // (from inboundChannel.close()) can let the latter win — and because
                // ClosedReceiveChannelException is not a CancellationException, coroutineScope
                // propagates it as a real failure, surfacing as 'Channel was closed' in the
                // transport layer and triggering a spurious reconnect attempt.
                signaling.close()
            }
            facade
        }

    /**
     * Suspend until the data channel opens, racing it against connection
     * failure and an overall [handshakeTimeoutMs] deadline.
     *
     * The facade exposes both [RtcPeerConnectionFacade.awaitDataChannelOpen]
     * and [RtcPeerConnectionFacade.awaitConnectionFailure]; awaiting only the
     * former would hang forever on the common ICE-failure path (e.g. STUN-only
     * behind symmetric NAT). Whichever resolves first wins: open → return,
     * failure → throw, neither within the deadline → [kotlinx.coroutines.TimeoutCancellationException].
     */
    private suspend fun awaitConnected(
        facade: RtcPeerConnectionFacade,
        handshakeTimeoutMs: Long,
    ) {
        withTimeout(handshakeTimeoutMs) {
            coroutineScope {
                val opened = async { facade.awaitDataChannelOpen() }
                val failed = async { facade.awaitConnectionFailure() }
                try {
                    select<Unit> {
                        opened.onAwait { }
                        failed.onAwait { cause -> throw cause }
                    }
                } finally {
                    opened.cancel()
                    failed.cancel()
                }
            }
        }
    }
}
