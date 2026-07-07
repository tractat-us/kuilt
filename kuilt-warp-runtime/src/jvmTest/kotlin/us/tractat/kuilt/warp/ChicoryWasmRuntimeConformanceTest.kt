package us.tractat.kuilt.warp

import us.tractat.kuilt.warp.test.WasmRuntimeConformanceSuite

/**
 * Binds the JVM [ChicoryWasmRuntime] to the shared [WasmRuntimeConformanceSuite] TCK: the
 * full load-guard / run-guard / OOB-vector contract runs here against the exact bytes every
 * other target verifies.
 */
class ChicoryWasmRuntimeConformanceTest : WasmRuntimeConformanceSuite() {
    override fun newRuntime(config: WasmSandboxConfig): WasmRuntime = ChicoryWasmRuntime(config)
}
