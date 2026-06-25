package us.tractat.kuilt.warp

/**
 * A single registered operation in the warp op registry.
 *
 * The contract is deliberately type-erased: arguments and results are opaque
 * [ByteArray]s. This keeps [OpRegistry] serialization-friendly and target-agnostic —
 * typed wrappers (encoding/decoding the [ByteArray] to/from a domain type) can be
 * layered on top by callers.
 *
 * An [Op] may suspend (e.g. to delegate CPU-bound work to a background dispatcher or
 * perform I/O), but must not rely on any external mutable state that outlives the call.
 *
 * @see OpRegistry
 * @see OpId
 */
public fun interface Op {
    public suspend fun invoke(args: ByteArray): ByteArray
}
