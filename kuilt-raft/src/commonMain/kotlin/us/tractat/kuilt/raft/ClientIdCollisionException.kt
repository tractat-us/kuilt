package us.tractat.kuilt.raft

/**
 * Thrown by a node using a caller-supplied **durable** [ClientId] when it observes another live
 * writer committing under that same identity (Raft §8 collision). Indicates an operational error —
 * two processes were handed one durable id. Fail loud; do not retry under the same id.
 *
 * A restarted node can also throw this on its own history, when a serial its earlier incarnation
 * issued reaches it only by replication: see [ClientIdentity.Durable]. It cannot tell the two apart.
 */
public class ClientIdCollisionException(message: String) : IllegalStateException(message)
