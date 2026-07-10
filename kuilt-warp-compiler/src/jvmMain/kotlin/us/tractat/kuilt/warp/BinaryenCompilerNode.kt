package us.tractat.kuilt.warp

/**
 * Register this node as a **real compiler node**: the [WarpNode.registerCompiler] compile
 * lambda is the given [optimizer] — in production a [BinaryenWasmOptimizer] running the bundled
 * `wasm-opt` subprocess. From this point on, a `compile(sourceHash, target, optLevel)` task
 * claimed by this node fetches the raw kernel, hands it to [optimizer], and publishes the leaner
 * module as a variant that gossips to the mesh (see [WarpNode.registerCompiler]).
 *
 * This is the thin seam between the ring-dispatched compile op ([kuilt-warp]) and the real WASM
 * toolchain ([BinaryenWasmOptimizer], [kuilt-warp-compiler]/jvm): the op mechanism lives in
 * `:kuilt-warp` (target-neutral, no toolchain), the real optimizer lives here (JVM/server only,
 * where a compiler node runs), and this function is the one line that wires them together.
 *
 * **Target is intentionally ignored.** `wasm-opt` is ABI-preserving and produces one optimized
 * module regardless of where it will eventually load — the variant's [Target] is recorded as
 * provenance by [WarpNode.registerCompiler] (via its [VariantKey]), it does not change the bytes
 * the optimizer emits. A future cross-target toolchain would branch on it here; today one
 * optimized module serves every target that can run the source.
 *
 * **Exception discipline.** The delegation is a bare `suspend` call: a [WasmOptimizationException]
 * from a failed `wasm-opt` exec propagates (unclaiming the compile task — fail-loud, never a
 * silent passthrough), and cancellation propagates too ([BinaryenWasmOptimizer.optimize] already
 * routes through `runCatchingCancellable`).
 *
 * Requires a `lazyFetch` capability, exactly like [WarpNode.registerCompiler] — throws
 * [IllegalStateException] otherwise. Call once at startup.
 *
 * @param optimizer The [WasmOptimizer] backing the compile op — [BinaryenWasmOptimizer] in
 *   production; a test may pass [PassthroughWasmOptimizer] or a fake.
 */
public fun WarpNode.registerBinaryenCompiler(optimizer: WasmOptimizer) {
    registerCompiler { source, _, optLevel -> optimizer.optimize(source, optLevel) }
}
