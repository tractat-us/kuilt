package us.tractat.kuilt.raft

/**
 * Thrown by a node using a caller-supplied **durable** [ClientId] when it observes another live
 * writer committing under that same identity (Raft §8 collision). Indicates an operational error —
 * two processes were handed one durable id. Fail loud; do not retry under the same id.
 */
public class ClientIdCollisionException(message: String) : IllegalStateException(message)
