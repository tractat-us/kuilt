package us.tractat.kuilt.warp

/**
 * A batch of op registrations, installable into an [OpRegistry] as one unit.
 *
 * The `kuilt-warp-ksp` symbol processor generates one registrar object (named
 * `WarpOps`) per package that declares [WarpOp]-annotated ops. Application startup
 * composes registrars from every op-carrying module into a single registry:
 *
 * ```kotlin
 * val registry = opRegistryOf(
 *     com.example.search.WarpOps,
 *     com.example.ranking.WarpOps,
 * )
 * ```
 *
 * Registrars cover **compiled-in** ops — the homogeneous-binary path where every peer
 * already holds the code and only names travel. Lazy-fetched wasm kernels are the
 * other path: they register themselves dynamically through the same [OpRegistry] when
 * their bytes arrive. The two paths share the registry and its fail-loud
 * duplicate-registration contract.
 *
 * @see WarpOp
 * @see opRegistryOf
 */
public fun interface OpRegistrar {

    /**
     * Registers every op this registrar carries into [registry].
     *
     * Delegates to [OpRegistry.register] per op, so installing the same registrar
     * twice — or two registrars sharing an op id — fails loud with
     * [IllegalStateException], exactly like a hand-written duplicate registration.
     */
    public fun registerInto(registry: OpRegistry)
}

/**
 * Builds an [OpRegistry] populated by [registrars], installed in the given order.
 *
 * The one-line startup surface for auto-registered ops:
 *
 * ```kotlin
 * val registry = opRegistryOf(WarpOps)
 * ```
 *
 * @param registrars The registrars to install, typically the generated `WarpOps`
 *   objects of every op-carrying package.
 * @return A fresh [OpRegistry] holding every registrar's ops.
 * @throws IllegalStateException if two registrars register the same [OpId].
 * @see OpRegistrar
 */
public fun opRegistryOf(vararg registrars: OpRegistrar): OpRegistry =
    OpRegistry().also { registry -> registrars.forEach { it.registerInto(registry) } }
