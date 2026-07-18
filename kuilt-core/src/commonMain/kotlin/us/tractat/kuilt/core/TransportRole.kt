package us.tractat.kuilt.core

/**
 * What role a transport plays. A single fabric may hold several roles at once
 * (Apple Multipeer is [WifiDirect] + [Bluetooth]; a Network.framework fabric is
 * [Discovery] + [Data]). Sealed so a novel fabric can add a case without editing
 * a closed enum.
 */
public sealed interface TransportRole {
    /** Finds peers/sessions (mDNS/Bonjour advertising & browsing). */
    public data object Discovery : TransportRole

    /** Carries application frames once a link is established. */
    public data object Data : TransportRole

    /** Wi-Fi via a shared access point — the "same Wi-Fi network" case. */
    public data object WifiLan : TransportRole

    /** Peer-to-peer Wi-Fi with no access point (AWDL / Wi-Fi Aware / Wi-Fi Direct). */
    public data object WifiDirect : TransportRole

    /** Bluetooth radio link. */
    public data object Bluetooth : TransportRole

    /** WebRTC data channel. */
    public data object WebRtc : TransportRole

    /** Reaches peers by relaying through a server. */
    public data object ServerRelay : TransportRole
}
