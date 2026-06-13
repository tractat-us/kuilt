package us.tractat.kuilt.core

/**
 * Establishes a [Seam] in the role of either an existing-session
 * joiner or a new-session opener. The factory hides discovery (mDNS,
 * MultipeerConnectivity advertising, WebSocket URL).
 *
 * The single abstract method is [weave]; [host] and [join] are default
 * wrappers. ADR-002.
 *
 * ## Usage
 *
 * Host a session, let a second peer join, and exchange a frame:
 *
 * @sample us.tractat.kuilt.core.sampleHostAndJoin
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

    /**
     * Whether this fabric can be attempted now. Default [FabricAvailability.Available];
     * fabrics gated on a runtime capability override.
     */
    public fun availability(): FabricAvailability = FabricAvailability.Available
}
