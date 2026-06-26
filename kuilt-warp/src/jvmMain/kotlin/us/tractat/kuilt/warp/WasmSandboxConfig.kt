package us.tractat.kuilt.warp

import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the sandboxed WASM execution environment.
 *
 * [maxMemoryPages] caps linear memory at load time ([ChicoryWasmRuntime] rejects oversize modules
 * and clamps the runtime limit); [executionTimeout] bounds each invocation's wall-clock CPU time
 * (a runaway guest is interrupted and surfaces as [WasmExecutionException]).
 *
 * @param maxMemoryPages Maximum linear-memory pages the guest may declare (1 page = 64 KiB).
 *   Must be in `1..[MemoryLimits.MAX_PAGES]` (65536).
 * @param executionTimeout Maximum wall-clock time allowed for a single [Op] invocation. Must be positive.
 */
public data class WasmSandboxConfig(
    public val maxMemoryPages: Int = 16,
    public val executionTimeout: Duration = 1.seconds,
) {
    init {
        require(maxMemoryPages in 1..MemoryLimits.MAX_PAGES) {
            "maxMemoryPages must be in 1..${MemoryLimits.MAX_PAGES}, was $maxMemoryPages"
        }
        require(executionTimeout.isPositive()) {
            "executionTimeout must be positive, was $executionTimeout"
        }
    }
}
