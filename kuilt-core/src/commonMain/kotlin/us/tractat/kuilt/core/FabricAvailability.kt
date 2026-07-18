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

    /**
     * The fabric may or may not be usable — the platform cannot report ground
     * truth right now (e.g. iOS gives no Wi-Fi SSID; a Local-Network permission
     * has not yet been probed). Distinct from a target-scoped-out fabric, which
     * is simply absent. Best-effort consumers should surface [reason] rather than
     * assume [Available] or [Unavailable].
     */
    public data class Unknown(public val reason: String) : FabricAvailability
}
