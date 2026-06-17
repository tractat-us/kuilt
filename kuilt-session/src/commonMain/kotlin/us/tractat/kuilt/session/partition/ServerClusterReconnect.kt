package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag

/**
 * Reconnect helper for a joiner that faces a static cluster of server endpoints.
 *
 * Connects to one endpoint at a time. On transport tear, advances to the next
 * endpoint per the [selector] (default: deterministic round-robin). On reconnect,
 * the caller presents the [pendingToken] — which survives endpoint changes because
 * it is keyed on [RoomId], not on any specific server identity.
 *
 * ## Usage
 *
 * ```kotlin
 * val reconnect = ServerClusterReconnect(
 *     endpoints = listOf(tag0, tag1, tag2),
 *     scope = roomScope,
 * )
 *
 * // Initial connection:
 * val seam = reconnect.connect(loom)
 * // ... admit handshake; on success, register the token:
 * reconnect.setToken(room.resumeToken!!)
 *
 * // On transport tear:
 * reconnect.onTransportTear()
 * val newSeam = reconnect.connect(loom)
 * // Present the token for resume on the new endpoint:
 * room.resume(reconnect.pendingToken()!!)
 * // On success, clear the token (it has been consumed):
 * reconnect.clearToken()
 * ```
 *
 * ## Thread safety
 *
 * All mutable state is guarded by an atomicfu [reentrantLock]. Suspend calls
 * ([connect]) are issued *outside* the locked section to comply with the
 * lock-discipline rule: no suspending code inside a locked region.
 *
 * @param endpoints Ordered list of server endpoint [Tag]s. Must be non-empty.
 * @param scope Coroutine scope that owns any background work launched by this
 *   helper. **Required** — callers must supply an appropriate scope; no default
 *   real-dispatcher scope is provided, in compliance with the no-real-dispatcher
 *   policy.
 * @param selector Strategy that determines which index to activate on each
 *   [advanceEndpoint] call. Default: deterministic round-robin starting at index 0.
 */
public class ServerClusterReconnect(
    private val endpoints: List<Tag>,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val selector: EndpointSelector = RoundRobinEndpointSelector(startIndex = 0),
) {
    init {
        require(endpoints.isNotEmpty()) { "endpoints must be non-empty" }
    }

    private val lock = reentrantLock()

    // Index of the current endpoint within [endpoints].
    private var currentIndex: Int = selector.next(endpoints.size)

    // The resume token set after a successful admit handshake.
    private var token: ResumeToken? = null

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

    /**
     * Register a [ResumeToken] acquired after a successful admit handshake.
     *
     * The token is keyed on [ResumeToken.roomId] and survives endpoint changes:
     * a subsequent [onTransportTear] + [connect] to a different endpoint still
     * returns this token via [pendingToken], allowing the caller to present it
     * to the new server for session resumption.
     */
    public fun setToken(resumeToken: ResumeToken) {
        lock.withLock { token = resumeToken }
    }

    /**
     * Returns the [ResumeToken] set by [setToken], or null if none is registered.
     *
     * Returns the same token regardless of how many endpoint changes have occurred
     * since the token was set — the token outlives endpoint rotation.
     */
    public fun pendingToken(): ResumeToken? = lock.withLock { token }

    /**
     * Clear the pending token after a successful resume.
     *
     * A token is consumed by a single successful [us.tractat.kuilt.session.Room.resume]
     * call. After that call succeeds, the caller must invoke [clearToken] so that
     * a subsequent tear does not re-present a stale token.
     */
    public fun clearToken() {
        lock.withLock { token = null }
    }
}
