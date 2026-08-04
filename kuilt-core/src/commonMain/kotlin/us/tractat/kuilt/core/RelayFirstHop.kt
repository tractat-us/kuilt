package us.tractat.kuilt.core

/**
 * The first-hop origin-spoofing rule, shared by every point that accepts a relay frame off a
 * fabric — safety-critical wherever a relayed identity is credited.
 *
 * A relayed frame carries its true [origin] *inside* the envelope, because the fabric stamps the
 * **relaying** peer as the sender rather than the peer that minted the frame. That inner field is
 * forgeable, so before it is credited to anything it is checked against the fabric-stamped
 * [sender]:
 *
 * - A frame from an **untrusted** peer ([sender] not in [trusted]) is accepted only if its [origin]
 *   *is* that sender — a peer may speak only for itself, never on another's behalf.
 * - A frame from a **trusted** relayer ([sender] in [trusted]) is believed to carry an
 *   already-validated [origin]; trusted relayers preserve identity.
 *
 * Generic in the id type because the two callers key on different ones: `:kuilt-cluster` passes
 * `NodeId` with the voter core as [trusted], and `:kuilt-session` passes [PeerId] with
 * **`emptySet()`** — a room has no trusted relayer tier, so there the rule degenerates to
 * `origin == sender`. That degeneracy is deliberate and pinned by test; the shared function exists
 * for the cluster's non-empty case and for one statement of the rule.
 *
 * Pure and dependency-free by design: it is the *only* piece of the star relay that belongs in the
 * contract module, because it is the only piece that needs nothing from it.
 *
 * @return `true` if the frame passes first-hop validation.
 */
public fun <Id> validFirstHop(sender: Id, origin: Id, trusted: Set<Id>): Boolean =
    sender in trusted || origin == sender
