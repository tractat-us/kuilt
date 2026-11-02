package us.tractat.kuilt.raft

/**
 * Thrown by a node using a caller-supplied **durable** [ClientId] when it observes another live
 * writer committing under that same identity (Raft §8 collision). Indicates an operational error —
 * two processes were handed one durable id. Fail loud; do not retry under the same id.
 *
 * A restarted node can also throw this on its own history, when a serial its earlier incarnation
 * issued reaches it only by replication: see [ClientIdentity.Durable]. It cannot tell the two apart.
 *
 * **A restart hides this rather than clearing it.** The foreign entry reached storage before it
 * committed, so a boot over that storage takes the foreign serial as this id's own and stays up, and
 * only the next serial above that floor throws again. A clean boot after this exception is therefore
 * no evidence that the id is unshared, and a supervisor that restarts on it erases each collision as
 * it happens. Find out who else holds the id before restarting.
 */
public class ClientIdCollisionException(message: String) : IllegalStateException(message)
