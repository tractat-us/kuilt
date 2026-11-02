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
     * Use the caller-supplied stable [clientId] and the serial floor [issuedThrough], both persisted
     * by the caller across restarts. Replaying the same `requestId` on a post-crash retry yields
     * cross-crash exactly-once. A serial under this id above everything the node knows of is an
     * operational error and surfaces as a [ClientIdCollisionException] rather than a silent re-mint.
     *
     * [issuedThrough] is the highest `requestId` this id has issued, across every incarnation, or `0`
     * for an id that has issued nothing. The node takes any serial at or below it as its own, so its
     * own history is not a collision however it arrives: replayed from retained storage, sent by
     * replication to a node that kept no log (`clusterClient` keeps its log in memory), or committed
     * by a leader after the crash that lost the forward. Nothing in the node's own storage can stand
     * in for it, since a snapshot can cut the last entry under the id and a forward can be in flight
     * when the process dies.
     *
     * **Where it must be durable: your own store, written ahead.** Commit each new `requestId` as the
     * new floor **before** calling `propose(command, requestId)`, and read it back on every boot. If
     * the process dies between the write and the call, the floor sits above a serial that was never
     * issued, which costs nothing: serials may have gaps. The other order is the one that loses a
     * command.
     *
     * **A stale floor, one behind what the id really issued, is not caught for you.** Two things can
     * follow, and the node cannot tell either from a second writer:
     *
     * - An own serial above the floor reaches the node, and it fails with
     *   [ClientIdCollisionException]. It does so on every boot until it is given a true floor.
     * - A `propose` raises the node's mark over that serial first, so nothing throws, and the call
     *   reuses a serial. A leader still holding the old entry refuses other bytes with
     *   [RequestIdConflictException]. Any other leader appends them and the call returns success,
     *   yet a consumer's [ClientSessionTable] drops the command as a duplicate.
     *
     * A floor set too high costs nothing for this id's own commands, but widens the blind spot below.
     *
     * **`propose(command)` is refused under this identity**, with an [IllegalStateException] before
     * anything is drawn or sent. A serial the node drew for itself cannot be on disk before it is
     * issued, so it could never be covered by a floor: the crash in between reads as a collision on
     * catch-up, or reuses the serial and loses the next command silently. Use `propose(command,
     * requestId)`, or [Auto] where at-least-once is enough.
     *
     * **A second writer is caught only above the mark.** The mark is the higher of [issuedThrough]
     * and whatever this incarnation has proposed. Another writer's serial above it throws, on every
     * boot, since the retained log no longer raises the mark. Its serial at or below the mark goes
     * unseen, and so does a serial two writers draw at once: `propose` returns success and a
     * consumer's [ClientSessionTable] drops the command. That gap is tracked by #3081.
     *
     * @throws IllegalArgumentException if [issuedThrough] is negative.
     * @sample us.tractat.kuilt.raft.sampleDurableClientIdentity
     */
    public data class Durable(public val clientId: ClientId, public val issuedThrough: Long) : ClientIdentity {
        init {
            require(issuedThrough >= 0L) { "issuedThrough must not be negative: $issuedThrough" }
        }
    }
}
