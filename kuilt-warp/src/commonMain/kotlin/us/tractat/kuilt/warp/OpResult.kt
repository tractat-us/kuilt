package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * The result of running an [Op] — opaque bytes (success) or a named error (failure),
 * wrapped so it is **safe to store in a CRDT**.
 *
 * The warp results board is an `ORMap<TaskId, LWWRegister<OpResult>>` replicated by a
 * [us.tractat.kuilt.quilter.Quilter]. Anti-entropy decides convergence by **structural
 * equality** of the replicated state (`merged == current`). A raw [ByteArray] cannot be used
 * directly: `ByteArray.equals` is *referential identity*, so two peers holding byte-identical
 * results would compare unequal forever — the boards would never register as converged and the
 * Quilters would ping-pong full-state messages indefinitely (a real CPU/network storm between
 * live peers, not merely a test hang).
 *
 * [OpResult] overrides [equals]/[hashCode] with [ByteArray.contentEquals]/[contentHashCode] and
 * includes [error] in both, so identical results compare equal and the lattice converges. Pairs
 * with [TaskDescriptor], which does the same for its `args`.
 *
 * **Terminal errors.** When an [Op] fails irrecoverably a caller creates `OpResult.failure(msg)`
 * rather than propagating an exception into the results board. [isError] distinguishes a failure
 * result from a success result with empty bytes. The [error] string is included in
 * [equals]/[hashCode] so two failure results with different messages are structurally distinct,
 * and a failure result never compares equal to a success result — CRDT convergence is preserved.
 */
@Serializable
public class OpResult private constructor(
    public val bytes: ByteArray,
    public val error: String? = null,   // non-null ⇒ terminal failure; bytes empty
) {
    /** Creates a success result wrapping [bytes]. */
    public constructor(bytes: ByteArray) : this(bytes, null)

    /** `true` if this result represents a terminal failure; `false` for a success result. */
    public val isError: Boolean get() = error != null

    public companion object {
        /** Creates a terminal-error result with the given [message] and empty bytes. */
        public fun failure(message: String): OpResult = OpResult(ByteArray(0), message)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is OpResult && bytes.contentEquals(other.bytes) && error == other.error)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (error?.hashCode() ?: 0)

    override fun toString(): String =
        if (error != null) "OpResult(error=$error)" else "OpResult(${bytes.size} bytes)"
}
