package us.tractat.kuilt.core

/**
 * Whether a [Loom]'s underlying transport fabric can be attempted on this runtime.
 * A fabric scoped out by target (e.g. WebRTC only present on wasmJs) is simply
 * absent — not [Unavailable]. [Unavailable] means present-but-not-usable-now.
 *
 * @sample us.tractat.kuilt.core.sampleFabricAvailability
 */
public sealed interface FabricAvailability {
    public data object Available : FabricAvailability

    public data class Unavailable(public val reason: String) : FabricAvailability
}
