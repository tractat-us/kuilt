package us.tractat.kuilt.core

/**
 * Establishes a [Seam] in the role of either an existing-session
 * joiner or a new-session opener. The factory hides discovery (mDNS,
 * MultipeerConnectivity advertising, WebSocket URL).
 *
 * The single abstract method is [weave]; [host] and [join] are default
 * wrappers, and [open] is a deprecated alias for [host]. ADR-002.
 */
public interface Loom {
    /** Establish a [Seam] according to [rendezvous] — either host a new session or join an existing one. */
    public suspend fun weave(rendezvous: Rendezvous): Seam

    /** Host / start a new session. */
    public suspend fun host(pattern: Pattern): Seam = weave(Rendezvous.New(pattern))

    /**
     * Join an existing session. The advertisement carries enough info
     * to reach the existing peer set.
     */
    public suspend fun join(tag: Tag): Seam = weave(Rendezvous.Existing(tag))

    /** Deprecated alias for [host]. */
    @Deprecated("Renamed to host()", ReplaceWith("host(config)"))
    public suspend fun open(config: Pattern): Seam = host(config)

    /**
     * Whether this fabric can be attempted now. Default [FabricAvailability.Available];
     * fabrics gated on a runtime capability override. See tractat-us/fireworks-compose#1299.
     */
    public fun availability(): FabricAvailability = FabricAvailability.Available
}
