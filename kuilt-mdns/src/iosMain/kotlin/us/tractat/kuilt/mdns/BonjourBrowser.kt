@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION")

package us.tractat.kuilt.mdns

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.darwin.NSObject

// NSNetServiceBrowser is deprecated since iOS 15 in favour of NWBrowser (Network.framework).
// NWBrowser browsing is available in K/N 2.3.x but `nw_browse_result_enumerate` (required to
// iterate over the result set) is missing from the generated platform bindings. NSNetServiceBrowser
// has complete K/N Foundation bindings and is used as the practical alternative.
private const val RESOLVE_TIMEOUT_S = 5.0

/**
 * One Bonjour service, **resolved**, in plain Kotlin types.
 *
 * [txt] is the advertised TXT dictionary with its `NSData` values decoded as UTF-8, and with any
 * entry that failed to decode dropped.
 *
 * [host] is nullable because a resolution can complete without producing a host name. Such a record
 * cannot become an [MDNSAdvertisement] (there is nowhere to dial), but it *can* still teach
 * `departures()` which peer id a service name belongs to, which is why the nullability lives here
 * rather than being filtered out at the seam.
 */
internal class BonjourRecord(
    val serviceName: String,
    val host: String?,
    val port: Int,
    val txt: Map<String, String>,
)

/** A live browse session. [stop] tears it down; calling it twice must be harmless. */
internal fun interface BonjourBrowseHandle {
    fun stop()
}

/** What one browse session reports back to whoever opened it. */
internal interface BonjourBrowseSink {

    /** A service was found **and resolved** — see [BonjourBrowser] on why the seam bundles the two. */
    fun onResolved(record: BonjourRecord)

    /**
     * A service went away.
     *
     * Only the name, because that is all Bonjour supplies: `didRemoveService` hands back an
     * unresolved [NSNetService] whose `TXTRecordData()` is null. So the peer id is knowable at
     * removal time only from state built on the resolve path.
     */
    fun onLost(serviceName: String)
}

/**
 * The slice of [NSNetServiceBrowser] that [MDNSServiceDiscoverer] uses, behind a seam.
 *
 * The seam exists because [NSNetServiceBrowser] cannot be driven deterministically from a test: it
 * only delivers callbacks while the main run loop is being pumped, and a `runTest` body on
 * Kotlin/Native blocks the very thread that would pump it. Without this indirection nothing in
 * `iosMain` can be bound to `DiscoverySourceConformanceSuite`, and #2400's fix would ship as
 * unverified as the bug it replaces. The real binding against live Bonjour stays covered by the
 * `-P`-gated `MDNSServiceDiscovererIosTest`.
 *
 * **[browse] resolves on the session's own account.** Each call opens an independent browser *and*
 * asks each service it finds to resolve, so a caller never has to hope somebody else asked. That is
 * deliberate placement rather than convenience: the `PeerDiscoverySource` contract requires
 * `departures()` to work when it is the only thing being collected, and the defect that requirement
 * exists to catch — a departure feed free-riding on the resolutions a concurrent `discoveries()`
 * collector triggered — is *unrepresentable* once every session resolves for itself.
 * `DiscoverySourceConformanceSuite`'s `departuresEmitsWithNoConcurrentDiscoveriesCollector` still
 * holds the remaining half of it: that `departures()` opens a session at all.
 */
internal fun interface BonjourBrowser {

    /**
     * Open one browse session for [serviceType] (in [MDNSServiceType.forNsNetServiceBrowser] form),
     * reporting to [sink] until the returned handle is stopped.
     */
    fun browse(
        serviceType: String,
        sink: BonjourBrowseSink,
    ): BonjourBrowseHandle
}

/**
 * The real [BonjourBrowser], backed by [NSNetServiceBrowser] on the main run loop.
 *
 * The browser is scheduled on [NSRunLoop.mainRunLoop] so callbacks fire regardless of which
 * dispatcher collects the flow; [MDNSServiceDiscoverer] correspondingly runs its `callbackFlow`
 * bodies on `Dispatchers.Main`.
 *
 * All state is local to a [browse] call — one browser, one delegate — so two sessions never share a
 * resolution, which is what makes two concurrently-collected feeds independent.
 */
internal object NetServiceBonjourBrowser : BonjourBrowser {

    override fun browse(
        serviceType: String,
        sink: BonjourBrowseSink,
    ): BonjourBrowseHandle {
        val delegate = ServiceDelegate(sink)
        val browser = NSNetServiceBrowser()
        browser.setDelegate(delegate)
        browser.scheduleInRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
        browser.searchForServicesOfType(serviceType, inDomain = "local.")

        return BonjourBrowseHandle {
            browser.stop()
            browser.removeFromRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
            browser.setDelegate(null)
        }
    }
}

/**
 * Translates one [NSNetServiceBrowser]'s callbacks into [BonjourBrowseSink] calls.
 *
 * It resolves every service it finds — see [BonjourBrowser] — and reports removals by name.
 */
private class ServiceDelegate(
    private val sink: BonjourBrowseSink,
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
    ) {
        // Only the name: a removal carries no TXT, which is the whole shape of #2400's fix.
        sink.onLost(didRemoveService.name())
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        @Suppress("UNCHECKED_CAST")
        val dict =
            sender.TXTRecordData()
                ?.let { NSNetService.dictionaryFromTXTRecordData(it) as Map<Any?, NSData?> }
                .orEmpty()

        sink.onResolved(
            BonjourRecord(
                serviceName = sender.name(),
                host = sender.hostName,
                port = sender.port().toInt(),
                txt = dict.decodeUtf8Values(),
            ),
        )
    }

    override fun netService(
        sender: NSNetService,
        didNotResolve: Map<Any?, *>,
    ) {}
}

/** Decodes a Bonjour TXT dictionary into plain strings, dropping anything that will not decode. */
private fun Map<Any?, NSData?>.decodeUtf8Values(): Map<String, String> =
    entries
        .mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            val text = value?.toUtf8String() ?: return@mapNotNull null
            name to text
        }
        .toMap()

private fun NSData.toUtf8String(): String? {
    if (length == 0UL) return null
    return bytes()?.reinterpret<ByteVar>()?.readBytes(length.toInt())?.decodeToString()
}
