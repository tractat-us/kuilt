package us.tractat.kuilt.mdns

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Discovers peers advertising [MDNSAdvertisement.SERVICE_TYPE] via
 * Bonjour / mDNS.
 *
 * Exposes a cold [Flow] of [MDNSAdvertisement]s: each emission is a newly
 * resolved service. The flow stays open until the collector's scope is
 * cancelled, at which point the JmDNS listener is removed.
 *
 * Implements [PeerDiscoverySource] so the lobby can treat mDNS as one of
 * several composed transports; direct callers keep the narrower
 * `Flow<MDNSAdvertisement>` return via Kotlin's covariant-return support.
 *
 * **Important:** JmDNS service resolution is timing-sensitive. Callers should
 * apply a suitable timeout or use `take(n)` when a bounded number of peers is
 * expected in tests.
 *
 * @param jmdns The [JmDNS] instance to listen on.
 */
public class MDNSServiceDiscoverer(
    private val jmdns: JmDNS,
) : PeerDiscoverySource {
    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    /**
     * Returns a [Flow] that emits an [MDNSAdvertisement] for each peer
     * that is discovered on the local network.
     *
     * Only services that carry valid [MDNSAdvertisement.TXT_KEY_PEER_ID] TXT
     * entries are emitted; malformed records are silently dropped.
     */
    override fun discoveries(): Flow<MDNSAdvertisement> =
        callbackFlow {
            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        // Request resolution — serviceResolved will fire with full info.
                        jmdns.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val host =
                            event.info
                                ?.inetAddresses
                                ?.firstOrNull()
                                ?.hostAddress ?: return
                        toAdvertisement(event.info, host)?.let { trySend(it) }
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        // No-op: handled by departures() flow.
                    }
                }

            jmdns.addServiceListener(MDNSAdvertisement.SERVICE_TYPE, listener)

            awaitClose { jmdns.removeServiceListener(MDNSAdvertisement.SERVICE_TYPE, listener) }
        }

    /**
     * Returns a [Flow] that emits a peer key for each peer that
     * de-registers from the local network.
     *
     * The peer key is the TXT `peerId` from the removed service record. Records
     * without a `peerId` TXT entry are silently dropped (they could never have
     * been emitted by [discoveries] either).
     */
    override fun departures(): Flow<String> =
        callbackFlow {
            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) = Unit

                    override fun serviceResolved(event: ServiceEvent) = Unit

                    override fun serviceRemoved(event: ServiceEvent) {
                        val peerId = event.info?.getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID)
                        if (peerId != null) trySend(peerId)
                    }
                }

            jmdns.addServiceListener(MDNSAdvertisement.SERVICE_TYPE, listener)

            awaitClose { jmdns.removeServiceListener(MDNSAdvertisement.SERVICE_TYPE, listener) }
        }

    /**
     * Parses a [ServiceInfo] and a resolved [host] address into an [MDNSAdvertisement].
     *
     * Returns `null` if the required [MDNSAdvertisement.TXT_KEY_PEER_ID] TXT entry
     * is absent. Exposed as `internal` so unit tests can verify parsing logic
     * without needing a real network-registered [ServiceInfo].
     */
    internal fun toAdvertisement(
        info: ServiceInfo,
        host: String,
    ): MDNSAdvertisement? {
        val peerId = info.getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID) ?: return null
        val wsPath =
            info.getPropertyString(MDNSAdvertisement.TXT_KEY_WS_PATH)
                ?: MDNSAdvertisement.DEFAULT_WS_PATH
        return MDNSAdvertisement(
            host = host,
            port = info.port,
            serverPeerId = PeerId(peerId),
            displayName = info.name,
            wsPath = wsPath,
            hostOs =
                info
                    .getPropertyString(MDNSAdvertisement.TXT_KEY_HOST_OS)
                    ?.let { MDNSAdvertisement.HostOs.fromTxt(it) },
            fabrics = info.getPropertyString(MDNSAdvertisement.TXT_KEY_FABRICS),
            mcPeer = info.getPropertyString(MDNSAdvertisement.TXT_KEY_MC_PEER),
            gameMinVersion = info.getPropertyString(MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION)?.toIntOrNull(),
            gameMaxVersion = info.getPropertyString(MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION)?.toIntOrNull(),
        )
    }
}
