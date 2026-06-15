package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Registers a peer as a Bonjour / mDNS service so that other peers
 * on the same LAN can discover it.
 *
 * TXT records carry the peer's [PeerId], WebSocket path, protocol version
 * (v2), and the v2 fabric-correlation keys — so that discoverers can build a
 * full [MDNSAdvertisement] without any out-of-band configuration.
 *
 * **Lifecycle:** call [register] once. Call [unregister] when the peer leaves
 * the session. Both are blocking JmDNS calls and should be called from an
 * IO dispatcher.
 *
 * @param serviceType The mDNS service type. Supply the canonical base form
 *   (e.g. `MDNSServiceType("_myapp._tcp")`) — the JmDNS-required `.local.`
 *   suffix is appended internally.
 * @param jmdns The [JmDNS] instance to register on.
 * @param displayName Human-readable service name (shown in Bonjour browsers).
 * @param port TCP port the local WebSocket server listens on.
 * @param selfId This peer's [PeerId] — embedded in TXT records for joiners.
 * @param wsPath WebSocket path to advertise (default: [MDNSAdvertisement.DEFAULT_WS_PATH]).
 * @param hostOs OS family of this host — written as [MDNSAdvertisement.TXT_KEY_HOST_OS].
 * @param fabrics Comma-separated transport labels this host accepts (e.g. `"ws,mc"`).
 * @param mcPeer Optional MultipeerConnectivity handle — include when `"mc"` is in [fabrics].
 * @param txtExtensions Arbitrary application-supplied TXT record key–value pairs that are
 *   written alongside the kuilt-reserved fields and recovered by [MDNSServiceDiscoverer].
 */
public class MDNSServiceAdvertiser(
    private val serviceType: MDNSServiceType,
    private val jmdns: JmDNS,
    private val displayName: String,
    private val port: Int,
    private val selfId: PeerId,
    private val wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    private val hostOs: MDNSAdvertisement.HostOs? = null,
    private val fabrics: String? = null,
    private val mcPeer: String? = null,
    private val txtExtensions: Map<String, String> = emptyMap(),
) {
    private var serviceInfo: ServiceInfo? = null

    /** Registers the service. Idempotent — calling twice re-registers. */
    public fun register() {
        val info = buildServiceInfo()
        serviceInfo = info
        jmdns.registerService(info)
    }

    /** Unregisters the service. Safe to call if never registered. */
    public fun unregister() {
        serviceInfo?.let { jmdns.unregisterService(it) }
        serviceInfo = null
    }

    private fun buildServiceInfo(): ServiceInfo =
        ServiceInfo.create(
            serviceType.forJmDns(),
            displayName,
            port,
            0,
            0,
            buildTxtMapWithMcPeer(),
        )

    private fun buildTxtMapWithMcPeer(): Map<String, String> {
        val base = buildTxtMap(selfId, wsPath, hostOs, fabrics, txtExtensions)
        return if (mcPeer != null) base + (MDNSAdvertisement.TXT_KEY_MC_PEER to mcPeer) else base
    }
}
