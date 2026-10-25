@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.websocket

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_t
import platform.Network.nw_path_status_unsatisfied
import platform.darwin.dispatch_queue_create

/**
 * Watches this Apple device's network path so a WebSocket seam can report when it becomes unusable.
 * Create with [appleConnectivityObserver], pass it to [KtorClientLoom], and [close] when done.
 *
 * The first reading is `null` until Network.framework reports. A satisfied path is [NetworkReachability.Reachable];
 * an unsatisfied path is [NetworkReachability.Unreachable]. A satisfiable path (requires a connection),
 * an invalid status, or an unrecognised status is [NetworkReachability.Indeterminate]. None of these readings
 * probes the relay or promises that the internet, DNS, or a particular server works.
 *
 * The caller owns this observer, and may share it across looms. Closing a seam does not close it.
 * [close] cancels observation once and retains the last reading; callbacks racing with or arriving
 * after close cannot change it. Create a new observer to resume observation.
 */
public class AppleConnectivityObserver internal constructor(
    private val monitor: ApplePathMonitor,
) : ConnectivityObserver, AutoCloseable {
    private val lock = reentrantLock()
    private var closed = false
    private val readings = MutableStateFlow<NetworkReachability?>(null)
    override val reachability: StateFlow<NetworkReachability?> = readings.asStateFlow()

    init {
        monitor.start { status ->
            lock.withLock {
                if (!closed) readings.value = when (status) {
                    nw_path_status_satisfied -> NetworkReachability.Reachable
                    nw_path_status_unsatisfied -> NetworkReachability.Unreachable
                    else -> NetworkReachability.Indeterminate
                }
            }
        }
    }

    /** Cancel the owned monitor once, retaining the last reading. Safe to call from any thread. */
    override fun close() {
        val cancel = lock.withLock {
            if (closed) false else {
                closed = true
                true
            }
        }
        // No platform call under the lock: cancellation may itself deliver a final callback.
        if (cancel) monitor.cancel()
    }
}

/**
 * Start a caller-owned Network.framework observer on iOS or macOS.
 * Pass it as [KtorClientLoom]'s `connectivity` and close it when all looms using it are done.
 * Until the first callback, its reading stays `null`.
 *
 * ```kotlin
 * val connectivity = appleConnectivityObserver()
 * val loom = KtorClientLoom(httpClient, connectivity = connectivity)
 * // Use the loom, then close the observer when its last consumer is done.
 * connectivity.close()
 * ```
 */
public fun appleConnectivityObserver(): AppleConnectivityObserver =
    AppleConnectivityObserver(NetworkPathMonitor())

/** Native boundary: tests deliver Apple's actual status constants through the registered callback. */
internal interface ApplePathMonitor {
    fun start(onStatus: (nw_path_status_t) -> Unit)
    fun cancel()
}

private class NetworkPathMonitor : ApplePathMonitor {
    private val monitor = checkNotNull(nw_path_monitor_create()) { "nw_path_monitor_create returned null" }
    private val queue = dispatch_queue_create("us.tractat.kuilt.websocket.connectivity", null)

    override fun start(onStatus: (nw_path_status_t) -> Unit) {
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path -> onStatus(nw_path_get_status(path)) }
    }

    override fun cancel() {
        nw_path_monitor_cancel(monitor)
    }
}
