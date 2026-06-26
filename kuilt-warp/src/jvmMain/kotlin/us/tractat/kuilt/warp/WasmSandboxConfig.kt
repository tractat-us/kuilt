package us.tractat.kuilt.warp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the sandboxed WASM execution environment.
 *
 * Only the happy-path parameters are wired here; import rejection, memory caps,
 * and timeout enforcement are added in Tasks 3–4.
 *
 * @param maxMemoryPages Maximum linear-memory pages the guest may declare (1 page = 64 KiB).
 * @param executionTimeout Maximum wall-clock time allowed for a single [Op] invocation.
 */
public data class WasmSandboxConfig(
    public val maxMemoryPages: Int = 16,
    public val executionTimeout: Duration = 1.seconds,
)
