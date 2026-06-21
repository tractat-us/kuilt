package us.tractat.kuilt.raft

/**
 * How a [RaftNode] obtains its Raft §8 client identity for proposal dedup.
 *
 * Present-vs-absent of an id is **not tuning** — it switches a functional path: an [Auto] id
 * re-mints on collision (at-least-once forwarding, no cross-crash dedup), whereas a [Durable] id
 * treats a collision as an operational error. Modelling it as a sealed choice rather than a nullable
 * [ClientId] makes that fork explicit at every call site (per the "optional ≠ tuning" convention) and
 * removes the `null`-means-auto idiom from the public API.
 */
public sealed interface ClientIdentity {
    /**
     * Mint a per-incarnation auto id (`ClientId.auto`) and **re-mint it on a detected collision**.
     * Gives at-least-once forwarding dedup within one process incarnation but no exactly-once across
     * a crash (the id changes on restart). The default for callers that don't persist an identity.
     */
    public data object Auto : ClientIdentity

    /**
     * Use the caller-supplied stable [clientId], persisted by the caller across restarts. Replaying
     * the same `requestId` on a post-crash retry yields cross-crash exactly-once. A collision under a
     * durable id is an operational error (two live writers sharing one id) and surfaces as a
     * [ClientIdCollisionException] rather than a silent re-mint.
     */
    public data class Durable(public val clientId: ClientId) : ClientIdentity
}
