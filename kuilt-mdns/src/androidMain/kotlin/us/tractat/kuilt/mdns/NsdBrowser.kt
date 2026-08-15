package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.oshai.kotlinlogging.KotlinLogging

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
 * **Resolution state is per browser instance** — one instance per [MDNSServiceDiscoverer] — **not
 * per registration and not per [NsdManager].** [NsdManager.resolveService] handles one resolution at
 * a time, and below API 31 a second concurrent one fails with
 * [NsdManager.FAILURE_ALREADY_ACTIVE]; this module's `minSdk` is 24. Both feeds of one discoverer
 * are separate registrations over the same browser, so the serialising queue is a field here rather
 * than a `browse`-local, and each queued entry carries the sink that asked so results still route
 * back to the right feed.
 *
 * **What that leaves open, stated plainly:** two [MDNSServiceDiscoverer]s built over the *same*
 * injected [NsdManager] — two service types is the obvious reason to have two — get two browsers,
 * two queues, and no serialisation between them. Nothing here closes that, and the honest reason is
 * that the alternative is worse: a process-global map keyed on the manager would retain it for the
 * life of the process, and an Android system-service manager is obtained per-`Context` and holds
 * one, so the map would be a `Context` leak with no eviction point. A weak-keyed variant avoids the
 * leak but buys only one increment — it still cannot serialise against another *process*, and the
 * exact scope of the platform's limit (per client, per process, or device-wide) is not something
 * this module can verify, least of all with [NsdManagerBrowser] itself executed by no test (#2407).
 *
 * So the failure is made **observable** instead of silently assumed away: [NsdManager.ResolveListener
 * .onResolveFailed] logs its error code. A resolution that fails and is not logged is
 * indistinguishable from a peer that never arrived — which is a permanent ghost, the exact bug
 * #1903 exists to fix. Tracked in #2408.
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

    /**
     * Hand the head of the queue to the platform, or return having found nothing to do.
     *
     * The [nsdManager] call is guarded because a **throw** from it is the one failure that does not
     * merely lose one resolution: `resolving` is set before the call and cleared only by a
     * [NsdManager.ResolveListener] callback, so an exception on the way in would leave the flag true
     * with no callback ever coming. Every later resolution — for **both** feeds of this browser,
     * which share one queue — would then return at the guard above, and every peer would become a
     * permanent ghost. That is the whole of the bug #1903 exists to fix, re-entering through the
     * error path of its own fix.
     *
     * Iterative rather than re-entrant on the catch, because the likely throw is a *systemic* one —
     * a manager in a bad state refuses every entry alike, not one unlucky one — so recovering by
     * recursion would take a stack frame per queued service exactly when the queue is longest.
     *
     * `catch (Throwable)` swallows deliberately and no cancellation can reach it: this method runs
     * only on platform callback threads ([NsdManager.DiscoveryListener.onServiceFound] via
     * [NsdBrowseSink.onFound]'s `requestResolve`, and the two [NsdManager.ResolveListener] methods),
     * never inside a coroutine, so there is no job whose cancellation could arrive here and none to
     * propagate one to. Rethrowing would unwind onto a platform thread and strand the queue — the
     * very wedge this guard exists to prevent.
     */
    private fun resolveNext() {
        while (true) {
            val next =
                synchronized(lock) {
                    if (resolving || queue.isEmpty()) return
                    resolving = true
                    queue.removeFirst()
                }
            try {
                startResolving(next)
                return
            } catch (failure: Throwable) {
                // Flag first, log second: the log reads off the very NsdServiceInfo that just
                // refused, so it is not the one statement here allowed to throw before the queue is
                // released again.
                synchronized(lock) { resolving = false }
                logger.debug(failure) {
                    "NSD resolution could not be started for ${next.info.serviceName}"
                }
            }
        }
    }

    /** Hand one entry to [NsdManager.resolveService]; every outcome after this returns is a callback. */
    private fun startResolving(next: PendingResolution) {
        nsdManager.resolveService(
            next.info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    // Logged, not swallowed: this service's peer id is now unknowable, so it can
                    // never be emitted by discoveries() nor removed by departures() — a permanent
                    // ghost. Silently dropped, that is indistinguishable from a peer that never
                    // arrived. FAILURE_ALREADY_ACTIVE (3) here means a concurrent resolution won:
                    // see the class KDoc on what this queue does and does not serialise.
                    logger.debug {
                        "NSD resolution failed for ${info.serviceName}: errorCode=$errorCode"
                    }
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
