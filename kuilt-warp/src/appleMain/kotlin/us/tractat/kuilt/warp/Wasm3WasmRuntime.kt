package us.tractat.kuilt.warp

/**
 * STUB (commit 1, RED): satisfies the [WasmRuntime] contract signature so the test compiles,
 * but performs no sandbox guards and returns empty bytes. Replaced by the real wasm3-backed
 * implementation in commit 2.
 */
public class Wasm3WasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime {
    override fun load(bytes: ByteArray): Op = Op { ByteArray(0) }
}
