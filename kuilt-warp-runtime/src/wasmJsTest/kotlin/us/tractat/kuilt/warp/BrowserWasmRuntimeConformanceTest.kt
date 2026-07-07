package us.tractat.kuilt.warp

import us.tractat.kuilt.warp.test.WasmRuntimeConformanceSuite

/**
 * Binds the browser [BrowserWasmRuntime] to the shared [WasmRuntimeConformanceSuite] TCK: the
 * full load-guard / run-guard / OOB-vector contract runs here against the exact bytes every
 * other target verifies.
 */
class BrowserWasmRuntimeConformanceTest : WasmRuntimeConformanceSuite() {
    override fun newRuntime(config: WasmSandboxConfig): WasmRuntime = BrowserWasmRuntime(config)
}
