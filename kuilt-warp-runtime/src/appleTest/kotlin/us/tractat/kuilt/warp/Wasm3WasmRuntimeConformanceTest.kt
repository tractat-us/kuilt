package us.tractat.kuilt.warp

import us.tractat.kuilt.warp.test.WasmRuntimeConformanceSuite

/**
 * Binds the native (Apple targets) [Wasm3WasmRuntime] to the shared
 * [WasmRuntimeConformanceSuite] TCK: the full load-guard / run-guard / OOB-vector contract
 * runs here against the exact bytes every other target verifies.
 */
class Wasm3WasmRuntimeConformanceTest : WasmRuntimeConformanceSuite() {
    override fun newRuntime(config: WasmSandboxConfig): WasmRuntime = Wasm3WasmRuntime(config)
}
