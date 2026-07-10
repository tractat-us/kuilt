package us.tractat.kuilt.warp

/**
 * Injectable contract for a wasm→wasm optimizer — the transform a **compiler node** runs
 * on a raw kernel before publishing the result as a gossiped variant.
 *
 * A compiler node fetches the source bytes for a [CompileRequest], calls [optimize], and
 * hands the leaner module to [WarpNode.publishVariant] under the requested [VariantKey].
 * Everything downstream — gossip, discover, fetch, tier-swap, the interpreted/compiled
 * counters — is unchanged; the optimizer only swaps the *transform* that produces the
 * variant bytes. It slots in exactly where [WarpNode.registerCompiler] takes its
 * `compile` lambda.
 *
 * **ABI-preserving.** A conforming implementation MUST return a still-runnable module that
 * preserves the warp ABI — the `warp_alloc`/`warp_run` exports a [WasmRuntime] requires —
 * so a weaker peer can load and run the optimized variant identically. Production optimizers
 * (`wasm-opt`/Binaryen) preserve exported functions at every level, so the ABI survives.
 *
 * **Level semantics.** The [OptLevel] selects the effort: `O2`/`O3` optimize for speed,
 * `Oz` for size; [OptLevel.O0] is a **passthrough** (no variant worth shipping), served by
 * [PassthroughWasmOptimizer]. A real optimizer typically rejects `O0` or returns the input
 * unchanged.
 *
 * **Where implementations live.** `:kuilt-warp` core stays dependency-free of any optimizer
 * toolchain — it holds only this interface and the [PassthroughWasmOptimizer] default. The
 * real `BinaryenWasmOptimizer` (bundled `wasm-opt`, extract-and-exec) ships in the opt-in
 * `:kuilt-warp-compiler` satellite, so the toolchain weight falls solely on compiler-node
 * operators. Tests inject [PassthroughWasmOptimizer] or a fake.
 *
 * @see WasmRuntime
 * @see PassthroughWasmOptimizer
 * @see WarpNode.registerCompiler
 */
public interface WasmOptimizer {
    /**
     * Optimize [bytes] at [optLevel], returning a distinct, still-runnable, ABI-preserving
     * module. The result's content hash differs from the source (unless [optLevel] is a
     * passthrough), making it a genuinely distinct, fetchable variant.
     *
     * Suspending because production implementations perform blocking I/O (extract and exec
     * a bundled `wasm-opt` binary) that must run off the caller's thread.
     */
    public suspend fun optimize(bytes: ByteArray, optLevel: OptLevel): ByteArray
}

/**
 * The identity optimizer: returns [bytes] unchanged at every level — the [OptLevel.O0]
 * (passthrough) semantics.
 *
 * A real seam for the paths that don't run a toolchain: the spike's fake-compiler flow and
 * tests keep a genuine [WasmOptimizer] to call without dragging in Binaryen. Produces no
 * speedup (the transform is a no-op) — real optimization is the `:kuilt-warp-compiler`
 * [WasmOptimizer] impl.
 */
public object PassthroughWasmOptimizer : WasmOptimizer {
    override suspend fun optimize(bytes: ByteArray, optLevel: OptLevel): ByteArray = bytes
}
