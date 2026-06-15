package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag

/**
 * [Tag] carrying enough information to connect via WebSocket
 * to a peer that was discovered via mDNS / Bonjour.
 *
 * Discovery via mDNS is handled by [MDNSServiceDiscoverer]; connection is
 * delegated to `:kuilt-websocket`. This advertisement bridges the two.
 *
 * ## Schema versioning
 *
 * [PROTOCOL_VERSION] is `"2"` (v2 schema). v1 readers silently ignore the new
 * keys per DNS-SD rules; v2 readers tolerate missing optional keys.
 *
 * ## Application extensions
 *
 * Callers may carry arbitrary application-specific metadata via [txtExtensions].
 * Each entry is written as an additional TXT record key–value pair. kuilt does
 * not interpret these values — they round-trip opaquely through the discover path
 * and arrive in the [MDNSAdvertisement] emitted by [MDNSServiceDiscoverer].
 *
 * @property host IP address or hostname of the advertising peer.
 * @property port TCP port the advertising peer's WebSocket server listens on.
 * @property serverPeerId The advertising peer's [PeerId].
 * @property displayName Human-readable service name from the mDNS TXT record.
 * @property wsPath WebSocket path to connect to (default: [DEFAULT_WS_PATH]).
 * @property hostOs OS family of the advertising host — for fabric selection.
 * @property fabrics Comma-separated transports the host accepts (e.g. `"ws,mc"`).
 * @property mcPeer Opaque MultipeerConnectivity handle — present only when `"mc"` is in [fabrics].
 * @property txtExtensions Arbitrary application-supplied TXT record key–value pairs.
 *   These are written into the mDNS TXT record alongside the kuilt-owned fields and
 *   recovered verbatim by [MDNSServiceDiscoverer]. Keys must not collide with the
 *   kuilt-reserved constants in [Companion].
 */
public data class MDNSAdvertisement(
    val host: String,
    val port: Int,
    val serverPeerId: PeerId,
    override val displayName: String,
    val wsPath: String = DEFAULT_WS_PATH,
    val hostOs: HostOs? = null,
    val fabrics: String? = null,
    val mcPeer: String? = null,
    val txtExtensions: Map<String, String> = emptyMap(),
) : Tag {
    /** The server's stable peer ID — unique across all mDNS advertisements. */
    override val peerKey: String get() = serverPeerId.value

    /** WebSocket URL derived from host, port, and path. */
    val wsUrl: String get() = "ws://$host:$port$wsPath"

    /** Host OS families that can appear in [TXT_KEY_HOST_OS]. */
    public enum class HostOs(
        public val txtValue: String,
    ) {
        Jvm("jvm"),
        Android("android"),
        Apple("apple"),
        ;

        public companion object {
            public fun fromTxt(value: String): HostOs? = entries.firstOrNull { it.txtValue == value }
        }
    }

    public companion object {
        public const val DEFAULT_WS_PATH: String = "/peer"

        // v1 TXT keys (required)
        public const val TXT_KEY_PEER_ID: String = "peerId"
        public const val TXT_KEY_WS_PATH: String = "wsPath"
        public const val TXT_KEY_PROTOCOL_VERSION: String = "protoVersion"

        // v2 TXT keys (optional for backward-compat; written by all v2 hosts)
        public const val TXT_KEY_HOST_OS: String = "hostOs"
        public const val TXT_KEY_FABRICS: String = "fabrics"
        public const val TXT_KEY_MC_PEER: String = "mcPeer"

        public const val PROTOCOL_VERSION: String = "2"

        /** Fabric label written into [TXT_KEY_FABRICS] for WebSocket. */
        public const val FABRIC_WS: String = "ws"

        /** Fabric label written into [TXT_KEY_FABRICS] for MultipeerConnectivity. */
        public const val FABRIC_MC: String = "mc"

        /** Fabric label written into [TXT_KEY_FABRICS] for Nearby Connections. */
        public const val FABRIC_NEARBY: String = "nearby"
    }
}
