package us.tractat.kuilt.webrtc

/**
 * ICE configuration handed to the browser's `RTCPeerConnection`.
 *
 * Defaults to one public STUN ([DefaultStun]); add [IceServer.Turn]
 * entries via a custom [IceConfig] for WAN/NAT-traversal play. kuilt
 * does not host TURN.
 */
public data class IceConfig(
    val iceServers: List<IceServer>,
    val iceTransportPolicy: IceTransportPolicy = IceTransportPolicy.All,
) {
    public companion object {
        /** One public STUN (Cloudflare). Suitable for LAN + most home NAT pairs. */
        public val DefaultStun: IceConfig =
            IceConfig(listOf(IceServer.stun("stun:stun.cloudflare.com:3478")))

        /** No ICE servers at all. Host candidates only — true same-LAN only. */
        public val NoServers: IceConfig = IceConfig(iceServers = emptyList())
    }
}

public sealed interface IceServer {
    public data class Stun(
        val url: String,
    ) : IceServer

    public data class Turn(
        val url: String,
        val username: String,
        val credential: String,
    ) : IceServer

    public companion object {
        public fun stun(url: String): Stun = Stun(url)
    }
}

public enum class IceTransportPolicy {
    /** Use any reachable candidate. */
    All,

    /** Use only relay (TURN) candidates. Forces traffic through a TURN server. */
    Relay,
}
