package us.tractat.kuilt.core.fabric

/**
 * A transport-agnostic stream of inbound peer [Connection]s — the "front door" of a hosted
 * session. Unlike `KtorServerLoom.nextLink()`, which collapses each accepted session into a
 * finished 2-peer [us.tractat.kuilt.core.Seam], [accept] yields the raw [Connection] (a hub
 * spoke) so a composer can bond many of them into one group view.
 */
public interface ConnectionSource {
    /** Suspends until the next inbound peer connection is accepted, then returns it. */
    public suspend fun accept(): Connection
}
