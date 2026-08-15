package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.core.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers peers on the local network via mDNS / Bonjour using Android's [NsdManager].
 *
 * Browse-only: hosting remains JVM-only via [MDNSServiceAdvertiser] / JmDNS.
 *
 * [NsdManager.resolveService] can only handle one resolution at a time; resolution requests are
 * serialised through an in-memory queue inside [NsdManagerBrowser].
 *
 * @param serviceType The mDNS service type. Supply the canonical base form
 *   (e.g. `MDNSServiceType("_myapp._tcp")`) — the NsdManager-required trailing
 *   `.` suffix is appended internally. Must match the type used by
 *   [MDNSServiceAdvertiser].
 */
public class MDNSServiceDiscoverer internal constructor(
    private val serviceType: MDNSServiceType,
    private val browser: NsdBrowser,
) : PeerDiscoverySource {

    /**
     * @param nsdManager The system NSD manager — obtain via
     *   `context.getSystemService(NsdManager::class.java)` and inject via your
     *   dependency injection container.
     */
    public constructor(
        serviceType: MDNSServiceType,
        nsdManager: NsdManager,
    ) : this(serviceType, NsdManagerBrowser(nsdManager))

    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    /**
     * Returns a [Flow] that emits an [MDNSAdvertisement] for each peer discovered
     * on the local network.
     *
     * **Self-discovery obligation (#1489):** NSD delivers a device's **own**
     * advertisement to its own browser, so this flow emits an [MDNSAdvertisement]
     * whose [MDNSAdvertisement.serverPeerId] is the local peer. A consumer that
     * both advertises and browses (a symmetric lobby) **must** filter out its own
     * id — `filter { it.serverPeerId != selfPeerId }` — or it will list, and dial,
     * itself. This raw source has no `selfPeerId` in scope so it cannot filter for you.
     */
    override fun discoveries(): Flow<MDNSAdvertisement> =
        callbackFlow {
            val handle =
                browser.browse(
                    serviceType.forNsd(),
                    object : NsdBrowseSink {
                        override fun onResolved(record: NsdRecord) {
                            record.toAdvertisement()?.let { trySend(it) }
                        }

                        // Handled by departures(), which opens a registration of its own.
                        override fun onLost(serviceName: String) {}

                        override fun onFailed(cause: Throwable) {
                            close(cause)
                        }
                    },
                )

            awaitClose { runCatchingCancellable { handle.stop() } }
        }

    /**
     * Returns a [Flow] that emits a peer key for each peer that de-registers from the local network.
     *
     * The peer key is the TXT `peerId` **remembered from that service's own resolution**, keyed by
     * the mDNS service name. It cannot be read from the removal event:
     * [NsdManager.DiscoveryListener.onServiceLost] hands back an unresolved [NsdServiceInfo] whose
     * `attributes` map is empty, so the name is the only thing a departure carries and the resolve
     * path is the only place the peer id is ever visible. Before #1903 this class discarded
     * `onServiceLost` outright and returned `emptyFlow()`, making every peer it discovered a
     * permanent ghost in `discoveryRoster`.
     *
     * This flow opens its **own** browse registration, which resolves on its own account (see
     * [NsdBrowser]). Collecting `departures()` alone is the ordinary case, not a contrived one —
     * `discoveryRoster` merges the two feeds, and `merge` subscribes to inner flows in separately
     * launched coroutines — and a feed that only works while a sibling collector holds a session
     * open is not a leave signal.
     *
     * A service name that never resolved emits nothing: it could never have been emitted by
     * [discoveries] either.
     *
     * The cost of that independence is one extra `NsdManager.discoverServices` registration per
     * collected feed, so a consumer collecting both — which is what `discoveryRoster` does — holds
     * two. NSD caps concurrent discovery requests ([NsdManager.FAILURE_MAX_LIMIT]); the cap is
     * per-process and generous, but a consumer standing up many discoverers at once is the shape
     * that would reach it.
     */
    override fun departures(): Flow<String> =
        callbackFlow {
            // Flow-local: one map per collection, created inside the callbackFlow block and
            // reachable only from this collection's own sink, so nothing is shared with another
            // collection or with discoveries(). Concurrent all the same — flow-locality removes the
            // sharing, not the concurrency, and NsdManager delivers its callbacks on a platform
            // thread. The atomic `remove` also makes a duplicated goodbye emit exactly once.
            val peerIdsByServiceName = ConcurrentHashMap<String, String>()
            val handle =
                browser.browse(
                    serviceType.forNsd(),
                    object : NsdBrowseSink {
                        override fun onResolved(record: NsdRecord) {
                            val peerId =
                                record.attributes[MDNSAdvertisement.TXT_KEY_PEER_ID] ?: return
                            peerIdsByServiceName[record.serviceName] = peerId
                        }

                        override fun onLost(serviceName: String) {
                            peerIdsByServiceName.remove(serviceName)?.let { trySend(it) }
                        }

                        override fun onFailed(cause: Throwable) {
                            close(cause)
                        }
                    },
                )

            awaitClose { runCatchingCancellable { handle.stop() } }
        }
}

/**
 * Parses a resolved [NsdRecord] into an [MDNSAdvertisement].
 *
 * Returns `null` when the record carries no [MDNSAdvertisement.TXT_KEY_PEER_ID] or no host address
 * — in either case there is no peer to list or nowhere to dial it.
 */
private fun NsdRecord.toAdvertisement(): MDNSAdvertisement? {
    val peerId = attributes[MDNSAdvertisement.TXT_KEY_PEER_ID] ?: return null
    val host = host ?: return null
    return MDNSAdvertisement(
        host = host,
        port = port,
        serverPeerId = PeerId(peerId),
        sessionName = serviceName,
        wsPath = attributes[MDNSAdvertisement.TXT_KEY_WS_PATH] ?: MDNSAdvertisement.DEFAULT_WS_PATH,
        hostOs =
            attributes[MDNSAdvertisement.TXT_KEY_HOST_OS]
                ?.let { MDNSAdvertisement.HostOs.fromTxt(it) },
        fabrics = attributes[MDNSAdvertisement.TXT_KEY_FABRICS],
        mcPeer = attributes[MDNSAdvertisement.TXT_KEY_MC_PEER],
        txtExtensions = extractExtensions(attributes),
        roomKey = attributes[MDNSAdvertisement.TXT_KEY_ROOM],
    )
}

private fun extractExtensions(attrs: Map<String, String>): Map<String, String> =
    attrs.filterKeys { it !in kuiltReservedTxtKeys }
