package us.tractat.kuilt.raft

/**
 * Thrown by a node using a caller-supplied **durable** [ClientId] when it observes a committed entry
 * under that identity with a serial above its mark (Raft §8 collision). Indicates an operational
 * error, and there are two, which the node cannot tell apart: two processes were handed one durable
 * id, or this one was handed a [ClientIdentity.Durable.issuedThrough] behind what the id really
 * issued. Fail loud; do not retry under the same id.
 *
 * **A restart does not clear this.** The offending entry reached storage before it committed, and the
 * log never raises the mark, so a boot under the same floor throws again on the same serial. Raising
 * the floor over it does boot, and from then on hides whichever writer it was: only do it once you
 * know that serial is this id's own.
 */
public class ClientIdCollisionException(message: String) : IllegalStateException(message)
