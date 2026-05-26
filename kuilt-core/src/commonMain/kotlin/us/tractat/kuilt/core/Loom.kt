package us.tractat.kuilt.core

/**
 * Establishes a [Seam] in the role of either an existing-session
 * joiner or a new-session opener. The factory hides discovery (mDNS,
 * MultipeerConnectivity advertising, WebSocket URL).
 */
public interface Loom {
    /** Open a new session. Returns once at least the local peer is ready. */
    public suspend fun open(config: Pattern): Seam

    /**
     * Join an existing session. The advertisement carries enough info
     * to reach the existing peer set.
     */
    public suspend fun join(advertisement: Tag): Seam

    /**
     * Whether this fabric can be attempted now. Default [FabricAvailability.Available];
     * fabrics gated on a runtime capability override. See tractat-us/fireworks-compose#1299.
     */
    public fun availability(): FabricAvailability = FabricAvailability.Available
}
