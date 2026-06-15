package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * Discovers peers on the local network via mDNS / Bonjour using Android's [NsdManager].
 *
 * Browse-only: hosting remains JVM-only via [MDNSServiceAdvertiser] / JmDNS.
 *
 * [NsdManager.resolveService] can only handle one resolution at a time; this
 * implementation serialises resolution requests through an in-memory queue.
 *
 * @param serviceType The mDNS service type. Supply the canonical base form
 *   (e.g. `MDNSServiceType("_myapp._tcp")`) — the NsdManager-required trailing
 *   `.` suffix is appended internally. Must match the type used by
 *   [MDNSServiceAdvertiser].
 * @param nsdManager The system NSD manager — obtain via
 *   `context.getSystemService(NsdManager::class.java)` and inject via your
 *   dependency injection container.
 */
public class MDNSServiceDiscoverer(
    private val serviceType: MDNSServiceType,
    private val nsdManager: NsdManager,
) : PeerDiscoverySource {
    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    override fun discoveries(): Flow<MDNSAdvertisement> =
        callbackFlow {
            val lock = Any()
            val queue = ArrayDeque<NsdServiceInfo>()
            var resolving = false

            fun resolveNext() {
                val next =
                    synchronized(lock) {
                        if (resolving || queue.isEmpty()) return
                        resolving = true
                        queue.removeFirst()
                    }
                nsdManager.resolveService(
                    next,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            info: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            synchronized(lock) { resolving = false }
                            val attrs = info.attributes
                            val peerId = attrs[MDNSAdvertisement.TXT_KEY_PEER_ID]?.decodeToString()
                            if (peerId != null) {
                                val wsPath =
                                    attrs[MDNSAdvertisement.TXT_KEY_WS_PATH]?.decodeToString()
                                        ?: MDNSAdvertisement.DEFAULT_WS_PATH
                                val host = info.host?.hostAddress
                                if (host != null) {
                                    trySend(
                                        MDNSAdvertisement(
                                            host = host,
                                            port = info.port,
                                            serverPeerId = PeerId(peerId),
                                            displayName = info.serviceName,
                                            wsPath = wsPath,
                                            hostOs =
                                                attrs[MDNSAdvertisement.TXT_KEY_HOST_OS]
                                                    ?.decodeToString()
                                                    ?.let { MDNSAdvertisement.HostOs.fromTxt(it) },
                                            fabrics =
                                                attrs[MDNSAdvertisement.TXT_KEY_FABRICS]
                                                    ?.decodeToString(),
                                            mcPeer =
                                                attrs[MDNSAdvertisement.TXT_KEY_MC_PEER]
                                                    ?.decodeToString(),
                                            txtExtensions = extractExtensions(attrs),
                                        ),
                                    )
                                }
                            }
                            resolveNext()
                        }
                    },
                )
            }

            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) {}

                    override fun onDiscoveryStopped(type: String) {}

                    override fun onStartDiscoveryFailed(
                        type: String,
                        code: Int,
                    ) {
                        close(Exception("NSD discovery failed: $code"))
                    }

                    override fun onStopDiscoveryFailed(
                        type: String,
                        code: Int,
                    ) {}

                    override fun onServiceFound(info: NsdServiceInfo) {
                        synchronized(lock) { queue += info }
                        resolveNext()
                    }

                    override fun onServiceLost(info: NsdServiceInfo) {}
                }

            nsdManager.discoverServices(
                serviceType.forNsd(),
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )

            awaitClose {
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
        }
}

private val kuiltReservedTxtKeys: Set<String> =
    setOf(
        MDNSAdvertisement.TXT_KEY_PEER_ID,
        MDNSAdvertisement.TXT_KEY_WS_PATH,
        MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION,
        MDNSAdvertisement.TXT_KEY_HOST_OS,
        MDNSAdvertisement.TXT_KEY_FABRICS,
        MDNSAdvertisement.TXT_KEY_MC_PEER,
    )

private fun extractExtensions(attrs: Map<String, ByteArray?>): Map<String, String> =
    attrs
        .filterKeys { it !in kuiltReservedTxtKeys }
        .mapNotNull { (key, value) -> value?.decodeToString()?.let { key to it } }
        .toMap()
