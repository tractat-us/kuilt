package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * One mDNS service, **resolved**, in plain Kotlin types.
 *
 * [attributes] is the advertised TXT map with its byte values decoded, and with any key whose value
 * was null dropped — so `attributes[k]` is exactly the old `attrs[k]?.decodeToString()`.
 *
 * [host] is nullable because resolution can succeed while producing no address. A record like that
 * cannot become an [MDNSAdvertisement] (there is nowhere to dial), but it *can* still teach
 * `departures()` which peer id the service name belongs to, which is why the nullability lives here
 * rather than being filtered out at the seam.
 */
internal class NsdRecord(
    val serviceName: String,
    val port: Int,
    val host: String?,
    val attributes: Map<String, String>,
)

/**
 * A live browse registration.
 *
 * [stop] is called exactly once, from the owning flow's `awaitClose`. It is deliberately **not**
 * documented as idempotent: [NsdManager.stopServiceDiscovery] throws on a listener it does not know,
 * so a second call would not be harmless and no caller here makes one.
 */
internal fun interface NsdBrowseHandle {
    fun stop()
}

/** What one browse registration reports back to whoever opened it. */
internal interface NsdBrowseSink {

    /**
     * A service was found, and is **unresolved**: only [serviceName] is known, because that is all
     * NSD's browse callback carries.
     *
     * Call [requestResolve] to ask for its TXT — the seam deliberately does *not* resolve on the
     * sink's behalf, and a sink that never calls it never learns anything but names. That placement
     * is the point: `departures()` must request its own resolutions rather than free-ride on the
     * ones a concurrent `discoveries()` collector triggered, and keeping the request on this side of
     * the seam is what makes the free-riding shape both *representable* and *red-able* under
     * `DiscoverySourceConformanceSuite.departuresEmitsWithNoConcurrentDiscoveriesCollector`.
     *
     * [requestResolve] is scoped to this callback because that is how the platform works: NSD
     * resolves the exact [NsdServiceInfo] it handed you, not a name looked up later.
     */
    fun onFound(
        serviceName: String,
        requestResolve: () -> Unit,
    )

    /** A resolution requested via [onFound] completed. */
    fun onResolved(record: NsdRecord)

    /**
     * A service went away.
     *
     * Only the name, because that is all the platform supplies:
     * [NsdManager.DiscoveryListener.onServiceLost] hands back an *unresolved* [NsdServiceInfo] whose
     * `attributes` map is empty. So the peer id is knowable at removal time only from state built on
     * the resolve path — which is the whole shape of #1903's fix.
     */
    fun onLost(serviceName: String)

    /** The registration could not be started at all. */
    fun onFailed(cause: Throwable)
}

/**
 * The slice of [NsdManager] that [MDNSServiceDiscoverer] uses, behind a seam.
 *
 * The seam exists because [NsdManager] is a `final` class with a package-private constructor: it
 * cannot be subclassed, faked, or constructed in a unit test, and [NsdServiceInfo]'s accessors throw
 * `Stub!` against `android.jar`. Without this indirection nothing in `androidMain` can be bound to
 * `DiscoverySourceConformanceSuite` at all, and #1903's fix would ship as unverified as the bug it
 * replaces. It is the same shape `:kuilt-nearby` already uses for Google Nearby (`NearbyApi` /
 * `GmsNearbyApi`).
 *
 * The seam is drawn **below** the decision to resolve, not above it: [browse] reports a bare name
 * and hands back a request, so "each feed asks for its own resolutions" stays a property of
 * [MDNSServiceDiscoverer], where the conformance suite can see it fail. What remains untested is
 * only what any seam leaves untested — that [NsdManagerBrowser] itself honours this contract
 * against the real platform. Nothing in this repo can drive Android's NSD stack in a unit test;
 * **#2407** tracks closing that.
 */
internal fun interface NsdBrowser {

    /**
     * Open one browse registration for [serviceType] (in [MDNSServiceType.forNsd] form), reporting
     * to [sink] until the returned handle is stopped.
     */
    fun browse(
        serviceType: String,
        sink: NsdBrowseSink,
    ): NsdBrowseHandle
}

/**
 * The real [NsdBrowser], backed by the platform [NsdManager].
 *
 * Registration state — the listener — is local to a [browse] call, so two registrations never share
 * a listener and each feed hears the platform independently.
 *
 * **Resolution state is per-manager, not per-registration.** [NsdManager.resolveService] can only
 * handle one resolution at a time, and that is a property of the manager rather than of any one
 * listener, so the serialising queue is a field here. It has to be: `discoveries()` and
 * `departures()` are separate registrations over the *same* manager, so a per-registration queue
 * would let two resolutions run concurrently and the second would fail
 * ([NsdManager.FAILURE_ALREADY_ACTIVE]) on every API level below 31 — this module's `minSdk` is 24.
 * Each queued entry carries the sink that asked, so results still route back to the right feed.
 */
internal class NsdManagerBrowser(
    private val nsdManager: NsdManager,
) : NsdBrowser {

    private class PendingResolution(
        val info: NsdServiceInfo,
        val sink: NsdBrowseSink,
    )

    private val lock = Any()
    private val queue = ArrayDeque<PendingResolution>()
    private var resolving = false

    override fun browse(
        serviceType: String,
        sink: NsdBrowseSink,
    ): NsdBrowseHandle {
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}

                override fun onDiscoveryStopped(type: String) {}

                override fun onStartDiscoveryFailed(
                    type: String,
                    code: Int,
                ) {
                    sink.onFailed(Exception("NSD discovery failed: $code"))
                }

                override fun onStopDiscoveryFailed(
                    type: String,
                    code: Int,
                ) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    sink.onFound(info.serviceName) { enqueue(PendingResolution(info, sink)) }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    sink.onLost(info.serviceName)
                }
            }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)

        return NsdBrowseHandle { nsdManager.stopServiceDiscovery(listener) }
    }

    private fun enqueue(pending: PendingResolution) {
        synchronized(lock) { queue += pending }
        resolveNext()
    }

    private fun resolveNext() {
        val next =
            synchronized(lock) {
                if (resolving || queue.isEmpty()) return
                resolving = true
                queue.removeFirst()
            }
        nsdManager.resolveService(
            next.info,
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
                    next.sink.onResolved(info.toRecord())
                    resolveNext()
                }
            },
        )
    }
}

/** Decodes a resolved [NsdServiceInfo] into the plain-typed [NsdRecord] the seam speaks. */
private fun NsdServiceInfo.toRecord(): NsdRecord =
    NsdRecord(
        serviceName = serviceName,
        port = port,
        host = host?.hostAddress,
        attributes =
            attributes
                .mapNotNull { (key, value) -> value?.decodeToString()?.let { key to it } }
                .toMap(),
    )
