package us.tractat.kuilt.webrtc

import kotlinx.coroutines.flow.Flow

/**
 * Out-of-band channel used to exchange [SignalingMessage]s between two
 * WebRTC endpoints during bootstrap. The library does not specify how
 * messages get from one endpoint to the other — that is the
 * implementation's concern (e.g. WebSocket relay, QR code, BroadcastChannel).
 *
 * Lives in `:transport-webrtc` rather than `:transport-core` because it
 * is WebRTC-specific scaffolding.
 */
public interface SignalingChannel {
    /**
     * Open a duplex signaling stream scoped to [room]. Exactly two endpoints
     * are expected to call [open] with the same [room] string; what they
     * see is each other's messages.
     *
     * The returned session is single-use; close and call [open] again to
     * reconnect.
     */
    public suspend fun open(room: String): SignalingSession
}

/**
 * One end of an open signaling exchange. Carries messages in both
 * directions until [close] is called or the peer disconnects.
 */
public interface SignalingSession {
    /** Messages sent by the other endpoint, in arrival order. */
    public val incoming: Flow<SignalingMessage>

    /** Send a message to the other endpoint. Suspends until accepted locally. */
    public suspend fun send(message: SignalingMessage)

    /** Close the session. Idempotent. */
    public suspend fun close()
}
