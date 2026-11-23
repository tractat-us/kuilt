package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag

/**
 * Reconnect helper for a joiner that faces a static cluster of server endpoints.
 *
 * Connects to one endpoint at a time. On transport tear, advances to the next
 * endpoint per the [selector] (default: deterministic round-robin). Only the server that admitted
 * the joiner holds a seat for its device key, so joining a different endpoint is a fresh admission
 * there.
 *
 * ## Usage
 *
 * ```kotlin
 * val reconnect = ServerClusterReconnect(
 *     endpoints = listOf(tag0, tag1, tag2),
 * )
 *
 * // Initial connection:
 * val seam = reconnect.connect(loom)
 *
 * // On transport tear:
 * reconnect.onTransportTear()
 * val newSeam = reconnect.connect(loom)
 * ```
 *
 * ## Thread safety
 *
 * All mutable state is guarded by an atomicfu [reentrantLock]. Suspend calls
 * ([connect]) are issued *outside* the locked section to comply with the
 * lock-discipline rule: no suspending code inside a locked region.
 *
 * This helper is caller-orchestrated: it tracks the current endpoint, and [connect] delegates
 * straight to [Loom.join] on the
 * calling coroutine. It launches no background work, so it owns no
 * [kotlinx.coroutines.CoroutineScope]. If a future iteration makes it manage the
 * connect → tear → reconnect loop itself, a required scope parameter is
 * reintroduced then (and actually used).
 *
 * @param endpoints Ordered list of server endpoint [Tag]s. Must be non-empty.
 * @param selector Strategy that determines which index to activate on each
 *   [advanceEndpoint] call. Default: deterministic round-robin starting at index 0.
 */
public class ServerClusterReconnect(
    private val endpoints: List<Tag>,
    private val selector: EndpointSelector = RoundRobinEndpointSelector(startIndex = 0),
) {
    init {
        require(endpoints.isNotEmpty()) { "endpoints must be non-empty" }
    }

    private val lock = reentrantLock()

    // Index of the current endpoint within [endpoints].
    private var currentIndex: Int = selector.next(endpoints.size)

    /**
     * The endpoint this helper is currently targeting.
     *
     * Thread-safe; reads the index under the lock.
     */
    public fun currentEndpoint(): Tag = lock.withLock { endpoints[currentIndex] }

    /**
     * Advance to the next endpoint as determined by [selector].
     *
     * Idempotent in the single-endpoint case (always stays at index 0).
     */
    public fun advanceEndpoint() {
        lock.withLock { currentIndex = selector.next(endpoints.size) }
    }

    /**
     * React to a transport tear by advancing to the next endpoint.
     *
     * Equivalent to [advanceEndpoint]; named separately for clarity at call sites
     * where the trigger is an explicit transport-close event.
     */
    public fun onTransportTear() {
        advanceEndpoint()
    }

    /**
     * Weave a new [Seam] against the [currentEndpoint] using [loom].
     *
     * The endpoint index is read under the lock, then [Loom.join] is called
     * outside the lock (suspend is never held under a locked region).
     *
     * @return the new [Seam] connected to [currentEndpoint].
     */
    public suspend fun connect(loom: Loom): Seam {
        val endpoint = currentEndpoint()
        return loom.join(endpoint)
    }
}
