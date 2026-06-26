package us.tractat.kuilt.warp

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
 * @param executionTimeout Maximum wall-clock time allowed for a single [Op] invocation.
 */
public data class WasmSandboxConfig(
    public val maxMemoryPages: Int = 16,
    public val executionTimeout: Duration = 1.seconds,
)
