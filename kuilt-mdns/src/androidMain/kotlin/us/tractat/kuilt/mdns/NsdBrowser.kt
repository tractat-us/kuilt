package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private val logger = KotlinLogging.logger("us.tractat.kuilt.mdns.NsdManagerBrowser")

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
 * [MDNSServiceDiscoverer], where the conformance suite can see it fail. [NsdPlatform] provides a
 * second, lower boundary for exercising [NsdManagerBrowser]'s queue and retry lifecycle. Unit tests
 * still do not drive Android's NSD stack or establish its contention scope.
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
 * Resolves discovered services, retrying platform contention while their collection stays live.
 *
 * Registrations share a browser-local queue. A [NsdManager.FAILURE_ALREADY_ACTIVE] response puts
 * only that service on a cancellable timer, so other services can proceed. Backoff doubles from
 * 100 ms to a 5 s cap, with jitter in the upper half of each window. No assumption about the
 * platform's contention scope, and no manager-wide registry, is needed.
 *
 * All lifecycle transitions, dispatches and sink deliveries share [lock], including stop/loss.
 * An already dispatched platform resolution cannot be cancelled through the legacy NSD API; its
 * callback releases the queue but is ignored if its registration or service is no longer live.
 */
internal class NsdManagerBrowser(
    private val platform: NsdPlatform,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) : NsdBrowser {

    constructor(nsdManager: NsdManager) : this(AndroidNsdPlatform(nsdManager))

    private class Registration(val sink: NsdBrowseSink, dispatcher: CoroutineDispatcher) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val services = mutableMapOf<String, PendingResolution>()
        var live = true
    }

    private class PendingResolution(val service: NsdService, val registration: Registration) {
        var requested = false
        var backoffMillis = 100L
        var retry: Job? = null
    }

    private class Attempt

    private val lock = Any()
    private val queue = ArrayDeque<PendingResolution>()
    private var resolving: Attempt? = null
    private var draining = false

    override fun browse(serviceType: String, sink: NsdBrowseSink): NsdBrowseHandle {
        val registration = Registration(sink, dispatcher)
        val handle = try {
            platform.discover(serviceType, object : NsdPlatform.Listener {
                override fun onFound(service: NsdService): Unit = synchronized(lock) {
                    if (!registration.live) return
                    val pending = registration.services.getOrPut(service.name) {
                        PendingResolution(service, registration)
                    }
                    sink.onFound(service.name) {
                        synchronized(lock) {
                            if (isLive(pending) && !pending.requested) {
                                pending.requested = true
                                queue += pending
                                drain()
                            }
                        }
                    }
                }

                override fun onLost(name: String): Unit = synchronized(lock) {
                    if (!registration.live) return
                    registration.services.remove(name)?.let { pending ->
                        pending.retry?.cancel()
                        pending.retry = null
                        queue.remove(pending)
                    }
                    sink.onLost(name)
                }

                override fun onFailed(cause: Throwable): Unit = synchronized(lock) {
                    if (!registration.live) return
                    invalidate(registration)
                    sink.onFailed(cause)
                }
            })
        } catch (failure: Throwable) {
            synchronized(lock) { invalidate(registration) }
            throw failure
        }
        return NsdBrowseHandle {
            synchronized(lock) { invalidate(registration) }
            handle.stop()
        }
    }

    /** Called under [lock]; identity also rejects callbacks from a lost-and-found incarnation. */
    private fun isLive(pending: PendingResolution): Boolean =
        pending.registration.live &&
            pending.registration.services[pending.service.name] === pending

    private fun retire(pending: PendingResolution) {
        if (isLive(pending)) pending.registration.services.remove(pending.service.name)
    }

    private fun invalidate(registration: Registration) {
        registration.live = false
        registration.scope.cancel()
        registration.services.clear()
        queue.removeAll { it.registration === registration }
    }

    /**
     * Called under [lock]. [draining] makes synchronous callbacks and throws drain iteratively.
     * Platform entry points are non-suspending; holding the lock through dispatch prevents a stop
     * from invalidating an entry between its liveness check and the platform call.
     */
    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (resolving == null && queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!isLive(next)) continue
                val attempt = Attempt()
                resolving = attempt
                try {
                    next.service.resolve(object : NsdResolutionListener {
                        override fun onFailed(errorCode: Int): Unit = synchronized(lock) {
                            if (resolving !== attempt) return
                            resolving = null
                            logger.debug {
                                "NSD resolution failed for ${next.service.name}: errorCode=$errorCode"
                            }
                            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && isLive(next)) {
                                retryLater(next)
                            } else {
                                retire(next)
                            }
                            drain()
                        }

                        override fun onResolved(record: NsdRecord): Unit = synchronized(lock) {
                            if (resolving !== attempt) return
                            resolving = null
                            try {
                                if (isLive(next)) {
                                    retire(next)
                                    next.registration.sink.onResolved(record)
                                }
                            } finally {
                                drain()
                            }
                        }
                    })
                } catch (failure: Throwable) {
                    // Nothing in the platform call suspends. A synchronous throw owes the queue
                    // a release even when no listener callback will arrive.
                    if (resolving === attempt) {
                        resolving = null
                        retire(next)
                    }
                    logger.debug(failure) { "NSD resolution could not be started for ${next.service.name}" }
                }
            }
        } finally {
            draining = false
        }
    }

    private fun retryLater(pending: PendingResolution) {
        val waitMillis = random.nextLong(pending.backoffMillis / 2, pending.backoffMillis + 1)
        pending.backoffMillis = (pending.backoffMillis * 2).coerceAtMost(5_000)
        // Install the job before it can run, including with an eager injected dispatcher.
        val job = pending.registration.scope.launch(start = CoroutineStart.LAZY) {
            delay(waitMillis)
            synchronized(lock) {
                pending.retry = null
                if (isLive(pending)) {
                    queue += pending
                    drain()
                }
            }
        }
        pending.retry = job
        job.start()
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

/** Platform calls only; queueing, retry and lifetime decisions belong to [NsdManagerBrowser]. */
internal fun interface NsdPlatform {
    fun discover(serviceType: String, listener: Listener): NsdBrowseHandle

    interface Listener {
        fun onFound(service: NsdService)
        fun onLost(name: String)
        fun onFailed(cause: Throwable)
    }
}

/** Keeps the exact unresolved platform service available for a later resolve attempt. */
internal interface NsdService {
    val name: String
    fun resolve(listener: NsdResolutionListener)
}

internal interface NsdResolutionListener {
    fun onFailed(errorCode: Int)
    fun onResolved(record: NsdRecord)
}

private class AndroidNsdPlatform(private val manager: NsdManager) : NsdPlatform {
    override fun discover(serviceType: String, listener: NsdPlatform.Listener): NsdBrowseHandle {
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                listener.onFailed(Exception("NSD discovery failed: $code"))
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                listener.onFound(object : NsdService {
                    override val name: String = info.serviceName

                    override fun resolve(listener: NsdResolutionListener) {
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                listener.onFailed(errorCode)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                listener.onResolved(info.toRecord())
                            }
                        })
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) = listener.onLost(info.serviceName)
        }
        manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
        return NsdBrowseHandle { manager.stopServiceDiscovery(discovery) }
    }
}
