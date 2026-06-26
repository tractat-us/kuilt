package us.tractat.kuilt.warp

import com.dylibso.chicory.wasm.types.MemoryLimits
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [WasmSandboxConfig] validation.
 *
 * Verifies that construction fails fast on obviously-invalid configurations
 * ([maxMemoryPages] = 0, above Chicory's max; [executionTimeout] ≤ 0) before any
 * misconfiguration can produce confusing downstream failures in [ChicoryWasmRuntime].
 */
class WasmSandboxConfigTest {

    @Test
    fun zeroMaxMemoryPagesIsRejected() {
        assertFailsWith<IllegalArgumentException> { WasmSandboxConfig(maxMemoryPages = 0) }
    }

    @Test
    fun negativeMaxMemoryPagesIsRejected() {
        assertFailsWith<IllegalArgumentException> { WasmSandboxConfig(maxMemoryPages = -1) }
    }

    @Test
    fun maxMemoryPagesAboveChicoryLimitIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            WasmSandboxConfig(maxMemoryPages = MemoryLimits.MAX_PAGES + 1)
        }
    }

    @Test
    fun exactlyChicoryMaxPagesIsAccepted() {
        WasmSandboxConfig(maxMemoryPages = MemoryLimits.MAX_PAGES) // boundary: must not throw
    }

    @Test
    fun minimumOnePageIsAccepted() {
        WasmSandboxConfig(maxMemoryPages = 1) // boundary: must not throw
    }

    @Test
    fun zeroExecutionTimeoutIsRejected() {
        assertFailsWith<IllegalArgumentException> { WasmSandboxConfig(executionTimeout = Duration.ZERO) }
    }

    @Test
    fun negativeExecutionTimeoutIsRejected() {
        assertFailsWith<IllegalArgumentException> { WasmSandboxConfig(executionTimeout = (-1).milliseconds) }
    }

    @Test
    fun defaultConfigIsValid() {
        assertAll(
            { WasmSandboxConfig() },
            { WasmSandboxConfig(maxMemoryPages = 16) },
        )
    }
}
