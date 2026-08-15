@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION")

package us.tractat.kuilt.mdns

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import io.github.oshai.kotlinlogging.KotlinLogging
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.darwin.NSObject
import us.tractat.kuilt.core.runCatchingCancellable

// NSNetServiceBrowser is deprecated since iOS 15 in favour of NWBrowser (Network.framework).
// NWBrowser browsing is available in K/N 2.3.x but `nw_browse_result_enumerate` (required to
// iterate over the result set) is missing from the generated platform bindings. NSNetServiceBrowser
// has complete K/N Foundation bindings and is used as the practical alternative.
private const val RESOLVE_TIMEOUT_S = 5.0

private val logger = KotlinLogging.logger("us.tractat.kuilt.mdns.BonjourBrowser")

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

/**
 * A live browse session.
 *
 * [stop] is called exactly once, from the owning flow's `awaitClose`. It is deliberately **not**
 * documented as idempotent — no caller here calls it twice, and promising more than the real
 * implementation delivers is how a doc starts lying.
 */
internal fun interface BonjourBrowseHandle {
    fun stop()
}

/** What one browse session reports back to whoever opened it. */
internal interface BonjourBrowseSink {

    /**
     * A service was found, and is **unresolved**: only [serviceName] is known, because that is all
     * Bonjour's browse callback carries.
     *
     * Call [requestResolve] to ask for its TXT — the seam deliberately does *not* resolve on the
     * sink's behalf, and a sink that never calls it never learns anything but names. That placement
     * is the point: `departures()` must request its own resolutions rather than free-ride on the
     * ones a concurrent `discoveries()` collector triggered, and keeping the request on this side of
     * the seam is what makes the free-riding shape both *representable* and *red-able* under
     * `DiscoverySourceConformanceSuite.departuresEmitsWithNoConcurrentDiscoveriesCollector`.
     *
     * [requestResolve] is scoped to this callback because that is how the platform works: you
     * resolve the exact [NSNetService] the browser handed you, not a name looked up later.
     */
    fun onFound(
        serviceName: String,
        requestResolve: () -> Unit,
    )

    /** A resolution requested via [onFound] completed. */
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
 * The seam is drawn **below** the decision to resolve, not above it: [browse] reports a bare name
 * and hands back a request, so "each feed asks for its own resolutions" stays a property of
 * [MDNSServiceDiscoverer], where the conformance suite can see it fail. What remains untested is
 * only what any seam leaves untested — that [NetServiceBonjourBrowser] itself honours this contract
 * against live Bonjour. The `-P`-gated [MDNSServiceDiscovererIosTest] covers `discoveries()` only,
 * and nothing yet drives a real `didRemoveService`; **#2407** tracks closing that.
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
        return NetServiceBrowseSession(browser, delegate)
    }
}

/**
 * One live [NSNetServiceBrowser], and **the Kotlin object that must outlive `browse`'s return**.
 *
 * Holding [delegate] here is the whole reason this is a class rather than a lambda.
 * `NSNetServiceBrowser.delegate` and `NSNetService.delegate` are `weak`/`assign` properties, so
 * Objective-C takes no strong reference to a Kotlin delegate: its lifetime is decided entirely by
 * Kotlin/Native's GC. With the delegate reachable only from a local of a function that has already
 * returned, it may be collected mid-session — after which callbacks silently stop, or the unowned
 * pointer dangles. Naming it as a field ties it to the session, and the session is reachable from
 * the owning flow's `awaitClose` for as long as the flow is collected.
 *
 * No test here can show this: every test drives a fake browser, so the real delegate is never
 * constructed. It rests on Kotlin/Native's interop rules, not on a green suite.
 */
private class NetServiceBrowseSession(
    private val browser: NSNetServiceBrowser,
    @Suppress("unused") private val delegate: ServiceDelegate,
) : BonjourBrowseHandle {

    /**
     * One guard per step, deliberately.
     *
     * Under a single `try` a throw from `stop()` would skip both `removeFromRunLoop` and
     * `setDelegate(null)`, leaking a run-loop source and leaving an unowned delegate pointer live —
     * exactly the "an obligation behind the guard is skipped" shape the repo's exception discipline
     * is about. Each step is independently owed, so each gets its own guard.
     */
    override fun stop() {
        runCatchingCancellable { browser.stop() }
            .onFailure { logger.debug(it) { "stopping the Bonjour browser failed" } }
        runCatchingCancellable {
            browser.removeFromRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
        }.onFailure { logger.debug(it) { "removing the Bonjour browser from the run loop failed" } }
        runCatchingCancellable { browser.setDelegate(null) }
            .onFailure { logger.debug(it) { "clearing the Bonjour browser delegate failed" } }
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
        sink.onFound(didFindService.name()) {
            didFindService.setDelegate(this)
            didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_S)
        }
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
