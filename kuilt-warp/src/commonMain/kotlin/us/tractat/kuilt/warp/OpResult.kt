package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * The result of running an [Op] — opaque bytes, wrapped so it is **safe to store in a CRDT**.
 *
 * The warp results board is an `ORMap<TaskId, LWWRegister<OpResult>>` replicated by a
 * [us.tractat.kuilt.quilter.Quilter]. Anti-entropy decides convergence by **structural
 * equality** of the replicated state (`merged == current`). A raw [ByteArray] cannot be used
 * directly: `ByteArray.equals` is *referential identity*, so two peers holding byte-identical
 * results would compare unequal forever — the boards would never register as converged and the
 * Quilters would ping-pong full-state messages indefinitely (a real CPU/network storm between
 * live peers, not merely a test hang).
 *
 * [OpResult] overrides [equals]/[hashCode] with [ByteArray.contentEquals]/[contentHashCode], so
 * identical results compare equal and the lattice converges. Pairs with [TaskDescriptor], which
 * does the same for its `args`.
 */
@Serializable
public class OpResult(public val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is OpResult && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "OpResult(${bytes.size} bytes)"
}
