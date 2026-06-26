package us.tractat.kuilt.warp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The maximum linear-memory pages a WASM module may declare, per the WebAssembly specification
 * (a 32-bit address space of 64 KiB pages = 4 GiB). Equals Chicory's `MemoryLimits.MAX_PAGES`;
 * defined here so the config is target-neutral and usable from `commonMain`.
 */
internal const val WASM_MAX_MEMORY_PAGES: Int = 65536

/**
 * Configuration for the sandboxed WASM execution environment.
 *
 * Target-neutral: every [WasmRuntime] implementation (JVM Chicory, browser WebAssembly API,
 * native wasm3) takes the same policy. [maxMemoryPages] caps linear memory at load time (a
 * conforming runtime rejects oversize modules and clamps the runtime limit); [executionTimeout]
 * bounds each invocation's wall-clock CPU time (a runaway guest is interrupted and surfaces as
 * [WasmExecutionException]).
 *
 * @param maxMemoryPages Maximum linear-memory pages the guest may declare (1 page = 64 KiB).
 *   Must be in `1..[WASM_MAX_MEMORY_PAGES]` (65536).
 * @param executionTimeout Maximum wall-clock time allowed for a single [Op] invocation. Must be positive.
 */
public data class WasmSandboxConfig(
    public val maxMemoryPages: Int = 16,
    public val executionTimeout: Duration = 1.seconds,
) {
    init {
        require(maxMemoryPages in 1..WASM_MAX_MEMORY_PAGES) {
            "maxMemoryPages must be in 1..$WASM_MAX_MEMORY_PAGES, was $maxMemoryPages"
        }
        require(executionTimeout.isPositive()) {
            "executionTimeout must be positive, was $executionTimeout"
        }
    }
}
