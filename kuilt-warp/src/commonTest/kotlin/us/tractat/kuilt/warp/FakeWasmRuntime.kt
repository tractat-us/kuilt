package us.tractat.kuilt.warp

/**
 * A test [WasmRuntime] that returns a fixed [Op] for any bytes — proving the tiering
 * *mechanism* (fetch → load → run → count) without compiling real wasm. The real runtimes
 * (Chicory / wasm3 / browser) are proven separately by the C3 dispatch tests.
 */
internal class FakeWasmRuntime(private val op: Op) : WasmRuntime {
    override fun load(bytes: ByteArray): Op = op
}
