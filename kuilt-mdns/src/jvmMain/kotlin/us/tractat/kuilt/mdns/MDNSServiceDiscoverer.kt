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
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers peers on the local network via Bonjour / mDNS.
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
 * @param serviceType The mDNS service type. Supply the canonical base form
 *   (e.g. `MDNSServiceType("_myapp._tcp")`) — the JmDNS-required `.local.`
 *   suffix is appended internally.
 * @param jmdns The [JmDNS] instance to listen on.
 */
public class MDNSServiceDiscoverer(
    private val serviceType: MDNSServiceType,
    private val jmdns: JmDNS,
) : PeerDiscoverySource {
    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    /**
     * Returns a [Flow] that emits an [MDNSAdvertisement] for each peer
     * that is discovered on the local network.
     *
     * Only services that carry valid [MDNSAdvertisement.TXT_KEY_PEER_ID] TXT
     * entries are emitted; malformed records are silently dropped.
     *
     * **Self-discovery obligation (#1489):** JmDNS delivers a device's **own**
     * advertisement to its own browser, so this flow emits an [MDNSAdvertisement]
     * whose [MDNSAdvertisement.serverPeerId] is the local peer. A consumer that
     * both advertises and browses (a symmetric lobby) **must** filter out its own
     * id — `filter { it.serverPeerId != selfPeerId }` — or it will list, and dial,
     * itself. This raw source has no `selfPeerId` in scope so it cannot filter for
     * you; the composed host entry points [MDNSPeerLinkFactory] and
     * [MDNSMultiAcceptHost], which know both ids, already apply this guard.
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

            jmdns.addServiceListener(serviceType.forJmDns(), listener)

            awaitClose { jmdns.removeServiceListener(serviceType.forJmDns(), listener) }
        }

    /**
     * Returns a [Flow] that emits a peer key for each peer that
     * de-registers from the local network.
     *
     * The peer key is the TXT `peerId` **remembered from that service's own resolution**, keyed by
     * the mDNS service name. It is not read from the removal event, which cannot supply it: JmDNS
     * fills `serviceRemoved`'s `ServiceInfo` with the qualified service name (the PTR rdata) rather
     * than the advertised TXT map, so `getPropertyString("peerId")` there is always null and this
     * flow emitted nothing, ever (#1917).
     *
     * This listener is also **self-sufficient**: it drives its own [JmDNS.requestServiceInfo] on
     * `serviceAdded` rather than relying on [discoveries] to have done it. Collecting `departures()`
     * alone is the ordinary case, not a contrived one — `discoveryRoster` merges the two feeds, and
     * `merge` subscribes to inner flows in separately launched coroutines — and a feed that only
     * works while a sibling collector holds a session open is not a leave signal.
     *
     * On this fabric that request is belt-and-braces rather than the whole story: an advertiser's
     * announcement usually carries SRV and TXT alongside the PTR, so JmDNS resolves from the
     * announcement and would fill the map unprompted. Asking removes the dependence on that — on a
     * partial or lost announcement it is the only thing that resolves — and it is what the
     * `PeerDiscoverySource` contract requires of every backend, most of which have no such
     * fallback.
     *
     * A service name that never resolved emits nothing — it could never have been emitted by
     * [discoveries] either.
     */
    override fun departures(): Flow<String> =
        callbackFlow {
            // Flow-local: one map per collection, created inside the callbackFlow block and
            // reachable only from this collection's own listener, so nothing is shared with another
            // collection or with discoveries(). Concurrent all the same — flow-locality removes the
            // sharing, not the concurrency, and JmDNS delivers these callbacks on its own threads.
            // The atomic `remove` also makes a duplicated goodbye emit exactly once.
            val peerIdsByServiceName = ConcurrentHashMap<String, String>()
            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        // Resolve on our own account — a lone departures() collector has nobody
                        // else to request resolution on its behalf. See the KDoc.
                        jmdns.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val peerId =
                            event.info?.getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID) ?: return
                        peerIdsByServiceName[event.name] = peerId
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        peerIdsByServiceName.remove(event.name)?.let { trySend(it) }
                    }
                }

            jmdns.addServiceListener(serviceType.forJmDns(), listener)

            awaitClose { jmdns.removeServiceListener(serviceType.forJmDns(), listener) }
        }

    /**
     * Parses a [ServiceInfo] and a resolved [host] address into an [MDNSAdvertisement].
     *
     * Returns `null` if the required [MDNSAdvertisement.TXT_KEY_PEER_ID] TXT entry
     * is absent. Exposed as `internal` so unit tests can verify parsing logic
     * without needing a real network-registered [ServiceInfo].
     *
     * Any TXT keys not recognized by kuilt are collected into
     * [MDNSAdvertisement.txtExtensions], preserving arbitrary caller-supplied metadata.
     */
    internal fun toAdvertisement(
        info: ServiceInfo,
        host: String,
    ): MDNSAdvertisement? {
        val peerId = info.getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID) ?: return null
        val wsPath =
            info.getPropertyString(MDNSAdvertisement.TXT_KEY_WS_PATH)
                ?: MDNSAdvertisement.DEFAULT_WS_PATH
        val extensions = extractExtensions(info)
        return MDNSAdvertisement(
            host = host,
            port = info.port,
            serverPeerId = PeerId(peerId),
            sessionName = info.name,
            wsPath = wsPath,
            hostOs =
                info
                    .getPropertyString(MDNSAdvertisement.TXT_KEY_HOST_OS)
                    ?.let { MDNSAdvertisement.HostOs.fromTxt(it) },
            fabrics = info.getPropertyString(MDNSAdvertisement.TXT_KEY_FABRICS),
            mcPeer = info.getPropertyString(MDNSAdvertisement.TXT_KEY_MC_PEER),
            txtExtensions = extensions,
            roomKey = info.getPropertyString(MDNSAdvertisement.TXT_KEY_ROOM),
        )
    }

    private fun extractExtensions(info: ServiceInfo): Map<String, String> {
        val reserved = kuiltReservedTxtKeys
        return info.propertyNames.asSequence()
            .filter { it !in reserved }
            .mapNotNull { key -> info.getPropertyString(key)?.let { key to it } }
            .toMap()
    }
}

