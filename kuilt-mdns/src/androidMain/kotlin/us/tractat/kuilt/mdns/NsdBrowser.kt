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

/** A live browse registration. [stop] tears it down; calling it twice must be harmless. */
internal fun interface NsdBrowseHandle {
    fun stop()
}

/** What one browse registration reports back to whoever opened it. */
internal interface NsdBrowseSink {

    /** A service was found **and resolved** — see [NsdBrowser] on why the seam bundles the two. */
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
 * **[browse] resolves on the registration's own account.** Each call opens an independent NSD
 * discovery registration *and* drives its own resolutions, so a caller never has to hope somebody
 * else asked. That is deliberate placement rather than convenience: the `PeerDiscoverySource`
 * contract requires `departures()` to work when it is the only thing being collected, and the
 * defect that requirement exists to catch — a departure feed that free-rides on the resolutions a
 * concurrent `discoveries()` collector triggered — is *unrepresentable* once every registration
 * resolves for itself. `DiscoverySourceConformanceSuite`'s
 * `departuresEmitsWithNoConcurrentDiscoveriesCollector` still holds the remaining half of it: that
 * `departures()` opens a registration at all.
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
 * All state is local to a [browse] call, so two registrations never share a queue, a listener, or a
 * resolution — which is what makes two concurrently-collected feeds independent.
 *
 * [NsdManager.resolveService] can only handle one resolution at a time, so requests are serialised
 * through an in-memory queue. That queue and its `synchronized` guarding are moved verbatim from
 * `MDNSServiceDiscoverer.discoveries()`, where they used to live.
 */
internal class NsdManagerBrowser(
    private val nsdManager: NsdManager,
) : NsdBrowser {

    override fun browse(
        serviceType: String,
        sink: NsdBrowseSink,
    ): NsdBrowseHandle {
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
                        sink.onResolved(info.toRecord())
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
                    sink.onFailed(Exception("NSD discovery failed: $code"))
                }

                override fun onStopDiscoveryFailed(
                    type: String,
                    code: Int,
                ) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(lock) { queue += info }
                    resolveNext()
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    sink.onLost(info.serviceName)
                }
            }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)

        return NsdBrowseHandle { nsdManager.stopServiceDiscovery(listener) }
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
