package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * A compilation target — the platform a compiled bobbin variant is built for.
 *
 * A weaker peer tiers up only to a variant whose [Target] matches its own runtime. The
 * iOS ceiling stays *interpret optimized wasm*: a compiler node may ship [IosArm64] an
 * optimized wasm→wasm variant, never native machine code (Apple forbids executing
 * externally-delivered machine code at all).
 */
@Serializable
public enum class Target { Jvm, Browser, MacosArm64, IosArm64 }

/**
 * Optimization level of a compiled bobbin variant. Higher wins when several variants exist
 * for the same [Target]. The spike's fake compiler produced a single level; the durable
 * [VariantKey] address carries the level so a real optimizer ([WasmOptimizer]) can publish
 * variants at distinct efforts for the same source.
 *
 * The real toolchain (D4) maps each level onto a `wasm-opt` invocation: `O2 → -O2`,
 * `O3 → -O3`, `Oz → -Oz` (size-oriented). [O0] is a **passthrough** — no variant worth
 * shipping — served by [PassthroughWasmOptimizer]. `wasm-opt` preserves exported functions,
 * so the `warp_alloc`/`warp_run` ABI survives every level.
 */
@Serializable
public enum class OptLevel { O0, O2, O3, Oz }
