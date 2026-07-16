@file:OptIn(ExperimentalForeignApi::class)

package spike.suite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_loopback
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import platform.Network.nw_path_t
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create
import kotlin.time.Duration.Companion.seconds

/**
 * A best-effort snapshot of the device's network posture, captured via `nw_path_monitor`. Attached to
 * every [SuiteReport] so a failing report is self-describing: "join FAILed with Wi-Fi off, cellular
 * on, path unsatisfied" reads the failure without a Mac.
 *
 * SSID is deliberately omitted — `CNCopyCurrentNetworkInfo` needs a Location entitlement the throwaway
 * spike does not carry. The interface flags (`wifi`/`cellular`/`wired`) plus expensive/constrained are
 * the entitlement-free signal that distinguishes "Wi-Fi LAN" from "AWDL-only" from "cellular".
 */
public data class EnvSnapshot(
    public val pathStatus: String,
    public val wifi: Boolean,
    public val cellular: Boolean,
    public val wired: Boolean,
    public val loopback: Boolean,
    public val expensive: Boolean,
    public val constrained: Boolean,
) {
    private val ifaces: String
        get() = buildList {
            if (wifi) add("wifi"); if (cellular) add("cell"); if (wired) add("wired"); if (loopback) add("lo")
        }.joinToString(",").ifEmpty { "none" }

    public val line: String
        get() = "env: path=$pathStatus ifaces=[$ifaces] expensive=$expensive constrained=$constrained"

    public companion object {
        public val UNKNOWN: EnvSnapshot = EnvSnapshot("unknown", false, false, false, false, false, false)
    }
}

/**
 * Start a transient `nw_path_monitor`, wait for its first update (bounded), snapshot the path, then
 * cancel the monitor. Returns [EnvSnapshot.UNKNOWN] if no update arrives in time.
 */
public suspend fun captureEnv(): EnvSnapshot {
    val monitor = nw_path_monitor_create()
    val queue = dispatch_queue_create("us.tractat.spike.suite.path", null)
    nw_path_monitor_set_queue(monitor, queue)
    val first = CompletableDeferred<EnvSnapshot>()
    nw_path_monitor_set_update_handler(monitor) { path: nw_path_t? ->
        if (path != null && !first.isCompleted) first.complete(snapshot(path))
    }
    nw_path_monitor_start(monitor)
    val result = withTimeoutOrNull(3.seconds) { first.await() } ?: EnvSnapshot.UNKNOWN
    nw_path_monitor_cancel(monitor)
    return result
}

private fun snapshot(path: nw_path_t): EnvSnapshot {
    val status = when (nw_path_get_status(path)) {
        nw_path_status_satisfied -> "satisfied"
        nw_path_status_unsatisfied -> "unsatisfied"
        nw_path_status_satisfiable -> "satisfiable"
        else -> "unknown"
    }
    return EnvSnapshot(
        pathStatus = status,
        wifi = nw_path_uses_interface_type(path, nw_interface_type_wifi),
        cellular = nw_path_uses_interface_type(path, nw_interface_type_cellular),
        wired = nw_path_uses_interface_type(path, nw_interface_type_wired),
        loopback = nw_path_uses_interface_type(path, nw_interface_type_loopback),
        expensive = nw_path_is_expensive(path),
        constrained = nw_path_is_constrained(path),
    )
}
