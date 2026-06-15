@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION")

package us.tractat.kuilt.mdns

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.darwin.NSObject
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource

// NSNetServiceBrowser is deprecated since iOS 15 in favour of NWBrowser (Network.framework).
// NWBrowser browsing is available in K/N 2.3.x but `nw_browse_result_enumerate` (required to
// iterate over the result set) is missing from the generated platform bindings. NSNetServiceBrowser
// has complete K/N Foundation bindings and is used as the practical alternative.
private const val RESOLVE_TIMEOUT_S = 5.0

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
public class MDNSServiceDiscoverer(
    private val serviceType: MDNSServiceType,
) : PeerDiscoverySource {
    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    override fun discoveries(): Flow<MDNSAdvertisement> =
        callbackFlow {
            val delegate = ServiceDelegate(onDiscovered = { trySend(it) })
            val browser = NSNetServiceBrowser()
            browser.setDelegate(delegate)
            browser.scheduleInRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
            browser.searchForServicesOfType(serviceType.forNsNetServiceBrowser(), inDomain = "local.")

            awaitClose {
                browser.stop()
                browser.removeFromRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
                browser.setDelegate(null)
            }
        }.flowOn(Dispatchers.Main)
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

private class ServiceDelegate(
    private val onDiscovered: (MDNSAdvertisement) -> Unit,
) : NSObject(),
    NSNetServiceBrowserDelegateProtocol,
    NSNetServiceDelegateProtocol {
    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        didFindService.setDelegate(this)
        didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_S)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didRemoveService: NSNetService,
        moreComing: Boolean,
    ) {}

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val host = sender.hostName ?: return
        val port = sender.port().toInt()
        val txtData = sender.TXTRecordData() ?: return

        @Suppress("UNCHECKED_CAST")
        val dict = NSNetService.dictionaryFromTXTRecordData(txtData) as Map<Any?, NSData?>

        val peerId = dict[MDNSAdvertisement.TXT_KEY_PEER_ID]?.toUtf8String() ?: return
        val wsPath =
            dict[MDNSAdvertisement.TXT_KEY_WS_PATH]?.toUtf8String()
                ?: MDNSAdvertisement.DEFAULT_WS_PATH

        val extensions = extractExtensions(dict)

        onDiscovered(
            MDNSAdvertisement(
                host = host,
                port = port,
                serverPeerId = PeerId(peerId),
                displayName = sender.name(),
                wsPath = wsPath,
                hostOs =
                    dict[MDNSAdvertisement.TXT_KEY_HOST_OS]
                        ?.toUtf8String()
                        ?.let { MDNSAdvertisement.HostOs.fromTxt(it) },
                fabrics = dict[MDNSAdvertisement.TXT_KEY_FABRICS]?.toUtf8String(),
                mcPeer = dict[MDNSAdvertisement.TXT_KEY_MC_PEER]?.toUtf8String(),
                txtExtensions = extensions,
            ),
        )
    }

    override fun netService(
        sender: NSNetService,
        didNotResolve: Map<Any?, *>,
    ) {}
}

private fun extractExtensions(dict: Map<Any?, NSData?>): Map<String, String> =
    dict
        .entries
        .mapNotNull { (key, value) ->
            val k = key as? String ?: return@mapNotNull null
            if (k in kuiltReservedTxtKeys) return@mapNotNull null
            val v = value?.toUtf8String() ?: return@mapNotNull null
            k to v
        }
        .toMap()

private fun NSData.toUtf8String(): String? {
    if (length == 0UL) return null
    return bytes()?.reinterpret<ByteVar>()?.readBytes(length.toInt())?.decodeToString()
}
