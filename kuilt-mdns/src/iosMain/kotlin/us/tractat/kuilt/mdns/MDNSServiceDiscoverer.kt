package us.tractat.kuilt.mdns

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSRunLoop
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext

/**
 * Discovers peers on the local network via mDNS / Bonjour using [NSNetServiceBrowser] on iOS.
 *
 * Browse-only: hosting remains JVM-only via [MDNSServiceAdvertiser] / JmDNS.
 *
 * The browser is scheduled on [NSRunLoop.mainRunLoop] so callbacks fire
 * regardless of which dispatcher collects the flow.
 *
 * @param serviceType The mDNS service type. Supply the canonical base form
 *   (e.g. `MDNSServiceType("_myapp._tcp")`) — [NSNetServiceBrowser] receives the
 *   type without the `local.` domain (which is passed separately to [NSNetServiceBrowser.searchForServicesOfType]).
 */
public class MDNSServiceDiscoverer internal constructor(
    private val serviceType: MDNSServiceType,
    private val browser: BonjourBrowser,
    /**
     * The context both feeds open and tear down their browse sessions in — [Dispatchers.Main] in
     * production, because [NSNetServiceBrowser] must be created and scheduled on the main run loop.
     *
     * Injectable only so a test can pass `EmptyCoroutineContext` and let the browse session run in
     * the collector's own context: a `runTest` body on Kotlin/Native occupies the main thread, so
     * work dispatched to [Dispatchers.Main] would never run and the conformance suite could not
     * observe this class at all. Production behaviour is unchanged — the public constructor pins
     * the dispatcher.
     */
    private val browseContext: CoroutineContext,
) : PeerDiscoverySource {

    public constructor(
        serviceType: MDNSServiceType,
    ) : this(serviceType, NetServiceBonjourBrowser, Dispatchers.Main)

    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    /**
     * Returns a [Flow] that emits an [MDNSAdvertisement] for each peer discovered
     * on the local network.
     *
     * **Self-discovery obligation (#1489):** Bonjour delivers a device's **own**
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
                    serviceType.forNsNetServiceBrowser(),
                    object : BonjourBrowseSink {
                        override fun onFound(
                            serviceName: String,
                            requestResolve: () -> Unit,
                        ) {
                            // Resolve on this session's own account — see BonjourBrowseSink.onFound.
                            requestResolve()
                        }

                        override fun onResolved(record: BonjourRecord) {
                            record.toAdvertisement()?.let { trySend(it) }
                        }

                        // Handled by departures(), which opens a session of its own.
                        override fun onLost(serviceName: String) {}
                    },
                )

            awaitClose {
                runCatchingCancellable { handle.stop() }
                    .onFailure { logger.debug(it) { "stopping the Bonjour session failed" } }
            }
        }.flowOn(browseContext)

    /**
     * Returns a [Flow] that emits a peer key for each peer that de-registers from the local network.
     *
     * The peer key is the TXT `peerId` **remembered from that service's own resolution**, keyed by
     * the Bonjour service name. It cannot be read from the removal event: `didRemoveService` hands
     * back an unresolved service whose `TXTRecordData()` is null, so the name is the only thing a
     * departure carries and the resolve path is the only place the peer id is ever visible. Before
     * #2400 this class discarded `didRemoveService` outright and returned `emptyFlow()`, making
     * every peer it discovered a permanent ghost in `discoveryRoster`.
     *
     * This flow opens its **own** browse session, which resolves on its own account (see
     * [BonjourBrowser]). Collecting `departures()` alone is the ordinary case, not a contrived
     * one — `discoveryRoster` merges the two feeds, and `merge` subscribes to inner flows in
     * separately launched coroutines — and a feed that only works while a sibling collector holds a
     * session open is not a leave signal.
     *
     * A service name that never resolved emits nothing: it could never have been emitted by
     * [discoveries] either.
     */
    override fun departures(): Flow<String> =
        callbackFlow {
            // Flow-local: one map per collection, created inside the callbackFlow block and
            // reachable only from this collection's own sink, so nothing is shared with another
            // collection or with discoveries(). Unlike the JVM and Android backends this one needs
            // no concurrent map: every Bonjour callback for this session is delivered on the main
            // run loop, the same place `browseContext` opens and closes the session, so the map has
            // exactly one accessing thread. That is a property of the run loop, not of the flow —
            // flow-locality removes the sharing, never the concurrency.
            val peerIdsByServiceName = mutableMapOf<String, String>()
            val handle =
                browser.browse(
                    serviceType.forNsNetServiceBrowser(),
                    object : BonjourBrowseSink {
                        override fun onFound(
                            serviceName: String,
                            requestResolve: () -> Unit,
                        ) {
                            // Resolve on our own account: a lone departures() collector has nobody
                            // else to request resolution on its behalf, and the peer id exists
                            // nowhere but the resolved record. See BonjourBrowseSink.onFound.
                            requestResolve()
                        }

                        override fun onResolved(record: BonjourRecord) {
                            val peerId = record.txt[MDNSAdvertisement.TXT_KEY_PEER_ID] ?: return
                            peerIdsByServiceName[record.serviceName] = peerId
                        }

                        override fun onLost(serviceName: String) {
                            peerIdsByServiceName.remove(serviceName)?.let { trySend(it) }
                        }
                    },
                )

            awaitClose {
                runCatchingCancellable { handle.stop() }
                    .onFailure { logger.debug(it) { "stopping the Bonjour session failed" } }
            }
        }.flowOn(browseContext)
}

private val logger = KotlinLogging.logger("us.tractat.kuilt.mdns.MDNSServiceDiscoverer")

/**
 * Parses a resolved [BonjourRecord] into an [MDNSAdvertisement].
 *
 * Returns `null` when the record carries no [MDNSAdvertisement.TXT_KEY_PEER_ID] or no host name —
 * in either case there is no peer to list or nowhere to dial it.
 */
private fun BonjourRecord.toAdvertisement(): MDNSAdvertisement? {
    val peerId = txt[MDNSAdvertisement.TXT_KEY_PEER_ID] ?: return null
    val host = host ?: return null
    return MDNSAdvertisement(
        host = host,
        port = port,
        serverPeerId = PeerId(peerId),
        sessionName = serviceName,
        wsPath = txt[MDNSAdvertisement.TXT_KEY_WS_PATH] ?: MDNSAdvertisement.DEFAULT_WS_PATH,
        hostOs = txt[MDNSAdvertisement.TXT_KEY_HOST_OS]?.let { MDNSAdvertisement.HostOs.fromTxt(it) },
        fabrics = txt[MDNSAdvertisement.TXT_KEY_FABRICS],
        mcPeer = txt[MDNSAdvertisement.TXT_KEY_MC_PEER],
        txtExtensions = txt.filterKeys { it !in kuiltReservedTxtKeys },
        roomKey = txt[MDNSAdvertisement.TXT_KEY_ROOM],
    )
}
