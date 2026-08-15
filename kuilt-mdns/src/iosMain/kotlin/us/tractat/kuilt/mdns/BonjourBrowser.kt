@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION")

package us.tractat.kuilt.mdns

import io.github.oshai.kotlinlogging.KotlinLogging
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
 * [delegate] is therefore held for two distinct reasons, and both are load-bearing: it must stay
 * *reachable* for the life of the session, and it must be *asked to detach* at the end of it. The
 * session's guarantee, once [stop] returns, is that no [NSNetService] anywhere still points at this
 * delegate and no resolution is still in flight — so nothing can call back into it after it becomes
 * collectable.
 *
 * No conformance test here can show any of this: those drive a fake browser, so the real delegate is
 * never constructed. The reachability half rests on Kotlin/Native's interop rules rather than on a
 * green suite; the detach half is pinned directly by `BonjourServiceDetachTest`, which drives this
 * delegate with synthetic services.
 */
private class NetServiceBrowseSession(
    private val browser: NSNetServiceBrowser,
    private val delegate: ServiceDelegate,
) : BonjourBrowseHandle {

    /**
     * Tear the session down: the browser first, then every service this delegate attached itself to.
     *
     * **Order is load-bearing.** The browser's delegate is cleared *before* the services are
     * detached, so a `didFindService` still queued on the run loop cannot reach [ServiceDelegate]
     * and attach a fresh service behind the detach. [ServiceDelegate.detachAll] does not rely on
     * that ordering alone — it also latches, so a late attach is refused rather than merely
     * unreachable — but the two together mean a service arriving during teardown is dropped at the
     * first of two independent gates rather than the second.
     *
     * **One guard per step, and a plain `catch` rather than `runCatchingCancellable`.** Under a
     * single `try` a throw from `stop()` would skip every later step, leaking a run-loop source and
     * leaving unowned delegate pointers live — exactly the "an obligation behind the guard is
     * skipped" shape the repo's exception discipline is about. This runs from a flow's `awaitClose`,
     * outside any suspending context, so there is no job here to cancel and `ensureActive()` would
     * be dead code; rethrowing a `CancellationException` could only abort the remaining cleanup,
     * which is the one thing this method exists to guarantee.
     */
    override fun stop() {
        guarded("stopping the Bonjour browser") { browser.stop() }
        guarded("clearing the Bonjour browser delegate") { browser.setDelegate(null) }
        guarded("removing the Bonjour browser from the run loop") {
            browser.removeFromRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
        }
        delegate.detachAll()
    }
}

/**
 * Run one cleanup step, logging rather than propagating — see [NetServiceBrowseSession.stop].
 *
 * Every caller is a teardown step that some later step depends on being reached.
 */
private inline fun guarded(
    what: String,
    step: () -> Unit,
) {
    try {
        step()
    } catch (failure: Throwable) {
        logger.debug(failure) { "$what failed" }
    }
}

/**
 * Translates one [NSNetServiceBrowser]'s callbacks into [BonjourBrowseSink] calls.
 *
 * It resolves every service it finds — see [BonjourBrowser] — and reports removals by name.
 *
 * **It also owns the lifetime of the delegate relationships it creates.** Setting itself as
 * an [NSNetService]'s delegate hands that service an *unowned* pointer back: `NSNetService.delegate`
 * is `assign`, so Objective-C takes no reference and this Kotlin object's lifetime is decided by
 * Kotlin/Native's GC alone. A `resolveWithTimeout` in flight is retained by the run loop for up to
 * [RESOLVE_TIMEOUT_S] — past the browser's own teardown — so without [detachAll] a service could
 * call back after this delegate had become unreachable and been collected, through freed memory.
 * That is a use-after-free, not a missing peer, and its trigger is ordinary: a collector cancelled
 * within five seconds of a peer appearing.
 *
 * **Threading.** [attached] and [detached] are plain, unguarded state, and that is sound only
 * because every path that touches them runs on the main run loop: Bonjour delivers its callbacks
 * there, and [detachAll] is reached from `awaitClose` inside `MDNSServiceDiscoverer`'s
 * `flowOn(browseContext)`, whose production value is `Dispatchers.Main`. None of these methods
 * suspends, so no callback can interleave with [detachAll]. **That pairing is an assumption, not an
 * enforcement** — the internal constructor lets a caller supply the real browser with some other
 * context, and nothing would red if one did. See the report's residual note.
 */
internal class ServiceDelegate(
    private val sink: BonjourBrowseSink,
) : NSObject(),
    NSNetServiceBrowserDelegateProtocol,
    NSNetServiceDelegateProtocol {

    /**
     * Services this delegate has pointed at itself and not yet detached.
     *
     * Entries are removed on a service's terminal callback as well as by [detachAll], so a
     * long-running browse over a busy network does not accumulate one reference per peer ever seen.
     */
    private val attached = mutableListOf<NSNetService>()

    /** Latched by [detachAll]: once torn down, this delegate never attaches to anything again. */
    private var detached = false

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        sink.onFound(didFindService.name()) {
            // Refused after teardown: attaching here would hand out exactly the unowned pointer
            // detachAll just finished reclaiming, and nothing would ever come back for it.
            if (detached) return@onFound
            didFindService.setDelegate(this)
            attached += didFindService
            didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_S)
        }
    }

    /**
     * Cancel every resolution still in flight and take back every unowned pointer to this delegate.
     *
     * `stop()` first, because it is what ends a resolution that has not timed out; then
     * `setDelegate(null)`, so a callback already queued finds no delegate to call. One guard per
     * item per the discipline in [NetServiceBrowseSession.stop] — a throw on one service must not
     * strand the rest, which would leave precisely the dangling pointer this exists to remove.
     *
     * The residual, stated rather than hidden: if `setDelegate(null)` itself throws, that one
     * service keeps its pointer and nothing retries. `setDelegate:` is a plain property write, so
     * this is a theoretical arm rather than a live one.
     */
    fun detachAll() {
        detached = true
        val services = attached.toList()
        attached.clear()
        services.forEach { service ->
            guarded("stopping an in-flight Bonjour resolution") { service.stop() }
            guarded("clearing a Bonjour service delegate") { service.setDelegate(null) }
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
        release(sender)

        val dict = sender.TXTRecordData()?.txtDictionary().orEmpty()

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
    ) {
        release(sender)
    }

    /**
     * A service has reached a terminal callback, so it will not call back again: stop tracking it
     * and give back the pointer now rather than at teardown.
     */
    private fun release(service: NSNetService) {
        if (!attached.remove(service)) return
        guarded("clearing a resolved Bonjour service delegate") { service.setDelegate(null) }
    }
}

/**
 * Parses a TXT record into its dictionary form, treating an unparseable one as empty.
 *
 * `dictionaryFromTXTRecordData:` returns `nil` for a TXT record Foundation cannot parse — including
 * an **empty** one, which a service that advertises no TXT at all has. The Kotlin/Native binding
 * declares the return non-null, so that `nil` arrives as a [NullPointerException] from the interop
 * not-null check rather than as a `null` this code could test for. Unhandled, one peer advertising a
 * malformed or absent TXT record takes down the whole browse: the exception escapes
 * `netServiceDidResolveAddress` into Objective-C, which is the discovery flow of every collector on
 * this fabric, for a condition entirely under a remote peer's control.
 *
 * A peer with no readable TXT simply has no peer id, and is dropped by [toAdvertisement] and by
 * `departures()` alike — which is the behaviour that was always intended for it.
 */
private fun NSData.txtDictionary(): Map<Any?, NSData?> {
    // The overwhelmingly common nil case, and the one reachable without a hostile peer.
    if (length == 0UL) return emptyMap()
    return try {
        @Suppress("UNCHECKED_CAST")
        NSNetService.dictionaryFromTXTRecordData(this) as Map<Any?, NSData?>
    } catch (failure: Throwable) {
        logger.debug(failure) { "unparseable Bonjour TXT record; treating it as empty" }
        emptyMap()
    }
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
