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
 * for the same [Target]. The spike's fake compiler produces a single level; the enum exists
 * so the durable [VariantKey] address survives into the real-toolchain epic (D4).
 */
@Serializable
public enum class OptLevel { O0, O2 }
