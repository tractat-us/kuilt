package us.tractat.kuilt.warp

/**
 * Marks a top-level `val` of type [Op] for **build-time auto-registration**.
 *
 * Annotate an op declared with [shuttle] and the `kuilt-warp-ksp` symbol processor
 * collects it into a generated `WarpOps` [OpRegistrar] for its package — no
 * hand-maintained `registry.register(...)` entry:
 *
 * ```kotlin
 * @WarpOp("reverse")
 * val reverse: Op = shuttle { args -> args.reversedArray() }
 *
 * // at startup — the only registration line a module needs:
 * val registry = opRegistryOf(WarpOps)
 * ```
 *
 * ## The op id
 *
 * [id] is the symbolic name that travels in task descriptors — it must be **stable
 * across deployments**, because every peer resolves it in its own registry.
 *
 * - **Explicit id (recommended):** `@WarpOp("reverse")`. Renaming the property later
 *   cannot silently change the wire name.
 * - **Derived id:** a bare `@WarpOp` uses the property's name (`val reverse` →
 *   `OpId("reverse")`). Convenient, but a rename then *is* a wire-protocol change —
 *   the processor cannot warn about that.
 *
 * ## Constraints (enforced at build time by the processor)
 *
 * - The target must be a **top-level, immutable `val`** whose type is [Op].
 * - It must be visible to generated code in the same module: `public` or `internal`,
 *   never `private` (file-private is invisible to the generated registrar).
 * - Ids must be unique within the module; a duplicate fails the build (the same
 *   invariant [OpRegistry.register] enforces at startup, moved to compile time).
 *
 * Retention is [AnnotationRetention.SOURCE]: the annotation exists only for the
 * processor. Nothing is reflected on at runtime and nothing lands in the binary.
 *
 * @property id The stable symbolic op name. Empty (the default) derives the id from
 *   the annotated property's name.
 * @see shuttle
 * @see OpRegistrar
 * @see opRegistryOf
 */
// Fully qualified: this package's warp compilation `Target` shadows kotlin.annotation.Target.
@kotlin.annotation.Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class WarpOp(val id: String = "")
